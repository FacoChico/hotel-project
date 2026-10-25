package com.example.hotel.hotel.controller;

import com.example.hotel.hotel.domain.Hotel;
import com.example.hotel.hotel.domain.Room;
import com.example.hotel.hotel.domain.RoomReservation;
import com.example.hotel.hotel.domain.RoomReservationState;
import com.example.hotel.hotel.repository.HotelRepository;
import com.example.hotel.hotel.repository.RoomRepository;
import com.example.hotel.hotel.repository.RoomReservationRepository;
import com.example.hotel.hotel.testutil.JwtTestKeys;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import lombok.SneakyThrows;
import tools.jackson.databind.json.JsonMapper;

import static com.example.hotel.hotel.domain.RoomReservationState.RELEASED;
import static com.example.hotel.hotel.domain.RoomReservationState.RESERVED;
import static com.example.hotel.hotel.testutil.JwtTestKeys.AUDIENCE;
import static com.example.hotel.hotel.testutil.JwtTestKeys.ISSUER;
import static com.example.hotel.hotel.testutil.JwtTestKeys.KEY_PAIR;
import static com.example.hotel.hotel.testutil.JwtTestKeys.SERVICE_AUDIENCE;
import static com.example.hotel.hotel.testutil.JwtTestKeys.sign;
import static com.nimbusds.jose.JWSAlgorithm.RS256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:room_statistics_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc(print = NONE)
class RoomStatisticsTest {
    private static final String STATISTICS_PATH = "/api/v1/rooms/statistics";
    private static final LocalDate START_DATE = LocalDate.of(2027, 1, 10);
    private static final LocalDate END_DATE = LocalDate.of(2027, 1, 20);
    private static final UUID REQUEST_ID = UUID.fromString("c7df6cee-2bd9-43b5-af66-54256c622a34");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private RoomReservationRepository reservationRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper mapper;

    @DynamicPropertySource
    private static void registerJwtProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @BeforeEach
    void clearReservationsAndRooms() {
        reservationRepository.deleteAll();
        roomRepository.deleteAll();
        hotelRepository.deleteAll();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @SneakyThrows
    void shouldReturnZeroOccupancyWhenRoomHasNoReservations(boolean available) {
        final var room = createRoom(createHotel("City Hotel"), "101", available, 7);

        final var result = statistics(query());
        final var body = mapper.readTree(result.andReturn().getResponse().getContentAsString());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].roomId").value(room.getId()))
            .andExpect(jsonPath("$.content[0].timesBooked").value(7))
            .andExpect(jsonPath("$.content[0].bookedNights").value(0))
            .andExpect(jsonPath("$.content[0].totalNights").value(10))
            .andExpect(jsonPath("$.content[0].occupancyRate").value(0.0))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(1));
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.get("content").get(0).size()).isEqualTo(5);
    }

    @ParameterizedTest
    @CsvSource({
        "2027-01-05, 2027-01-10, 0, 0.0",
        "2027-01-08, 2027-01-12, 2, 0.2",
        "2027-01-05, 2027-01-25, 10, 1.0",
        "2027-01-12, 2027-01-16, 4, 0.4",
        "2027-01-18, 2027-01-25, 2, 0.2",
        "2027-01-20, 2027-01-25, 0, 0.0",
        "2027-01-10, 2027-01-20, 10, 1.0",
        "2027-01-15, 2027-01-16, 1, 0.1"
    })
    @SneakyThrows
    void shouldCountIntersectionWhenReservationDatesVary(LocalDate startDate, LocalDate endDate,
                                                         long expectedNights, double expectedRate) {
        final var room = createRoom(createHotel("City Hotel"), "101", true, 1);
        createReservation(room, 42L, startDate, endDate, RESERVED);

        final var result = statistics(query());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].bookedNights").value(expectedNights))
            .andExpect(jsonPath("$.content[0].totalNights").value(10))
            .andExpect(jsonPath("$.content[0].occupancyRate").value(expectedRate));
    }

    @Test
    @SneakyThrows
    void shouldSumActiveIntersectionsWhenRoomHasSeveralReservations() {
        final var room = createRoom(createHotel("City Hotel"), "101", true, 9);
        createReservation(room, 41L, START_DATE.minusDays(2), START_DATE.plusDays(2), RESERVED);
        createReservation(room, 42L, START_DATE.plusDays(5), START_DATE.plusDays(8), RESERVED);
        createReservation(room, 43L, START_DATE.plusDays(8), END_DATE.plusDays(5), RESERVED);
        createReservation(room, 44L, START_DATE.plusDays(2), START_DATE.plusDays(5), RELEASED);
        createReservation(room, 45L, null, null, RELEASED);

        final var result = statistics(query());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].timesBooked").value(9))
            .andExpect(jsonPath("$.content[0].bookedNights").value(7))
            .andExpect(jsonPath("$.content[0].totalNights").value(10))
            .andExpect(jsonPath("$.content[0].occupancyRate").value(0.7))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @SneakyThrows
    void shouldIgnoreReleasedReservationWhenDatesArePresentOrAbsent(boolean hasDates) {
        final var room = createRoom(createHotel("City Hotel"), "101", true, 1);
        createReservation(room, 42L, hasDates ? START_DATE : null, hasDates ? END_DATE : null, RELEASED);

        final var result = statistics(query());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].timesBooked").value(1))
            .andExpect(jsonPath("$.content[0].bookedNights").value(0))
            .andExpect(jsonPath("$.content[0].occupancyRate").value(0.0));
    }

    @ParameterizedTest
    @CsvSource({
        "2028-02-28, 2028-03-02, 2028-02-29, 2028-03-01, 1, 3, 0.3333333333333333",
        "2027-12-30, 2028-01-02, 2027-12-31, 2028-01-01, 1, 3, 0.3333333333333333",
        "2027-01-10, 2027-01-11, 2027-01-10, 2027-01-11, 1, 1, 1.0",
        "2027-01-10, 2027-01-13, 2027-01-10, 2027-01-12, 2, 3, 0.6666666666666666"
    })
    @SneakyThrows
    void shouldCalculateFractionWhenReportLengthVaries(LocalDate reportStart, LocalDate reportEnd,
                                                       LocalDate reservationStart, LocalDate reservationEnd,
                                                       long expectedNights, long expectedTotal, double expectedRate) {
        final var room = createRoom(createHotel("City Hotel"), "101", true, 1);
        createReservation(room, 42L, reservationStart, reservationEnd, RESERVED);
        final var parameters = Map.of("startDate", reportStart.toString(), "endDate", reportEnd.toString());

        final var result = statistics(parameters);
        final var body = mapper.readTree(result.andReturn().getResponse().getContentAsString());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].bookedNights").value(expectedNights))
            .andExpect(jsonPath("$.content[0].totalNights").value(expectedTotal));
        assertThat(body.get("content").get(0).get("occupancyRate").doubleValue())
            .isCloseTo(expectedRate, within(1e-12));
    }

    @Test
    @SneakyThrows
    void shouldIncludeUnavailableRoomWhenItHasHistoricalOccupancy() {
        final var room = createRoom(createHotel("City Hotel"), "101", false, 1);
        createReservation(room, 42L, LocalDate.of(2020, 1, 10), LocalDate.of(2020, 1, 15), RESERVED);
        final var parameters = Map.of("startDate", "2020-01-10", "endDate", "2020-01-20");

        final var result = statistics(parameters);

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].roomId").value(room.getId()))
            .andExpect(jsonPath("$.content[0].bookedNights").value(5))
            .andExpect(jsonPath("$.content[0].occupancyRate").value(0.5))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @SneakyThrows
    void shouldFilterStatisticsWhenHotelIdIsProvided() {
        final var selectedHotel = createHotel("City Hotel");
        final var otherHotel = createHotel("Park Hotel");
        final var selectedRoom = createRoom(selectedHotel, "101", true, 1);
        final var otherRoom = createRoom(otherHotel, "101", true, 10);
        createReservation(selectedRoom, 42L, START_DATE, START_DATE.plusDays(2), RESERVED);
        createReservation(otherRoom, 43L, START_DATE, END_DATE, RESERVED);

        final var result = statistics(queryWith("hotelId", selectedHotel.getId().toString()));

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].roomId").value(selectedRoom.getId()))
            .andExpect(jsonPath("$.content[0].bookedNights").value(2))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @SneakyThrows
    void shouldIncludeAllHotelsWhenHotelFilterIsAbsent() {
        final var firstRoom = createRoom(createHotel("City Hotel"), "101", true, 1);
        final var secondRoom = createRoom(createHotel("Park Hotel"), "101", false, 0);
        createReservation(firstRoom, 42L, START_DATE, END_DATE, RESERVED);

        final var result = statistics(query());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].roomId").value(firstRoom.getId()))
            .andExpect(jsonPath("$.content[1].roomId").value(secondRoom.getId()))
            .andExpect(jsonPath("$.content[1].bookedNights").value(0))
            .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @SneakyThrows
    void shouldPaginateByRoomIdWhenCountsAndOccupancyDiffer() {
        final var hotel = createHotel("City Hotel");
        final var firstRoom = createRoom(hotel, "101", true, 9);
        final var secondRoom = createRoom(hotel, "102", true, 1);
        final var thirdRoom = createRoom(hotel, "103", true, 5);
        final var fourthRoom = createRoom(hotel, "104", false, 0);
        createReservation(firstRoom, 42L, START_DATE.minusDays(2), START_DATE.plusDays(2), RESERVED);
        createReservation(secondRoom, 43L, START_DATE, END_DATE, RESERVED);
        final var secondPageQuery = queryWith("size", "2");
        secondPageQuery.put("page", "1");

        final var firstPage = statistics(queryWith("size", "2"));
        final var secondPage = statistics(secondPageQuery);

        firstPage.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].roomId").value(firstRoom.getId()))
            .andExpect(jsonPath("$.content[0].bookedNights").value(2))
            .andExpect(jsonPath("$.content[1].roomId").value(secondRoom.getId()))
            .andExpect(jsonPath("$.content[1].bookedNights").value(10))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalElements").value(4));
        secondPage.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].roomId").value(thirdRoom.getId()))
            .andExpect(jsonPath("$.content[0].bookedNights").value(0))
            .andExpect(jsonPath("$.content[1].roomId").value(fourthRoom.getId()))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    @SneakyThrows
    void shouldReturnEmptyPageWhenOffsetExceedsRoomCount() {
        createRoom(createHotel("City Hotel"), "101", true, 0);
        final var parameters = queryWith("page", Integer.toString(Integer.MAX_VALUE));
        parameters.put("size", "100");

        final var result = statistics(parameters);

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.page").value(Integer.MAX_VALUE))
            .andExpect(jsonPath("$.size").value(100))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @SneakyThrows
    void shouldReturnEmptyPageWhenNoRoomsExist() {
        final var result = statistics(query());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @SneakyThrows
    void shouldReturnEmptyPageWhenHotelDoesNotExist() {
        createRoom(createHotel("City Hotel"), "101", true, 0);

        final var result = statistics(queryWith("hotelId", Long.toString(Long.MAX_VALUE)));

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @SneakyThrows
    void shouldPreserveLedgerAndCounterWhenStatisticsAreRead() {
        final var room = createRoom(createHotel("City Hotel"), "101", true, 1);
        createReservation(room, 42L, START_DATE, END_DATE, RESERVED);

        final var result = statistics(query());

        result.andExpect(status().isOk());
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(reservationRepository.findByBookingIdAndRoomId(42L, room.getId()).orElseThrow().getState())
            .isEqualTo(RESERVED);
        assertThat(roomRepository.findById(room.getId()).orElseThrow().getTimesBooked()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidQueries")
    @SneakyThrows
    void shouldRejectStatisticsWhenParametersAreInvalid(Map<String, String> parameters, String code) {
        final var result = statistics(parameters);

        result.andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    @SneakyThrows
    void shouldReturnUnauthorizedWhenTokenIsMissing() {
        final var request = get(STATISTICS_PATH);
        query().forEach(request::param);

        final var result = mockMvc.perform(request);

        result.andExpect(status().isUnauthorized())
            .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @SneakyThrows
    void shouldDenyStatisticsWhenUserHasNoAdminRole() {
        final var result = authenticatedStatistics(query(), userToken("USER"));

        result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @SneakyThrows
    void shouldReturnUnauthorizedWhenTokenBelongsToService() {
        final var now = Instant.now();
        final var token = sign(Map.of("sub", "booking-service", "iss", ISSUER, "aud", List.of(SERVICE_AUDIENCE),
            "iat", Date.from(now), "exp", Date.from(now.plusSeconds(120)), "scope", "reservations:write"), KEY_PAIR, RS256);

        final var result = authenticatedStatistics(query(), token);

        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private Hotel createHotel(String name) {
        return hotelRepository.saveAndFlush(new Hotel(name, "Central Street, 15"));
    }

    private Room createRoom(Hotel hotel, String number, boolean available, long timesBooked) {
        final var room = roomRepository.saveAndFlush(new Room(hotel, number, available));
        jdbcTemplate.update("UPDATE rooms SET times_booked = ? WHERE id = ?", timesBooked, room.getId());
        return room;
    }

    private void createReservation(Room room, long bookingId, LocalDate startDate,
                                   LocalDate endDate, RoomReservationState state) {
        reservationRepository.saveAndFlush(new RoomReservation(room, bookingId, REQUEST_ID, startDate, endDate, state));
    }

    private ResultActions statistics(Map<String, String> parameters) {
        return authenticatedStatistics(parameters, userToken("ADMIN"));
    }

    @SneakyThrows
    private ResultActions authenticatedStatistics(Map<String, String> parameters, String token) {
        final var request = get(STATISTICS_PATH).header(AUTHORIZATION, "Bearer " + token);
        parameters.forEach(request::param);
        return mockMvc.perform(request);
    }

    private static String userToken(String role) {
        final var now = Instant.now();
        return sign(Map.of("sub", "42", "iss", ISSUER, "aud", List.of(AUDIENCE), "roles", List.of(role),
            "iat", Date.from(now), "exp", Date.from(now.plusSeconds(3600))), KEY_PAIR, RS256);
    }

    private static Map<String, String> query() {
        return Map.of("startDate", START_DATE.toString(), "endDate", END_DATE.toString());
    }

    private static Map<String, String> queryWith(String name, String value) {
        final var parameters = new LinkedHashMap<>(query());
        parameters.put(name, value);
        return parameters;
    }

    private static Stream<Arguments> invalidQueries() {
        return Stream.of(
            arguments(Map.of(), "INVALID_REQUEST_PARAMETERS"),
            arguments(Map.of("startDate", START_DATE.toString()), "INVALID_REQUEST_PARAMETERS"),
            arguments(Map.of("endDate", END_DATE.toString()), "INVALID_REQUEST_PARAMETERS"),
            arguments(queryWith("startDate", ""), "INVALID_REQUEST_PARAMETERS"),
            arguments(queryWith("startDate", "invalid"), "INVALID_REQUEST_PARAMETERS"),
            arguments(queryWith("startDate", "2027-02-30"), "INVALID_REQUEST_PARAMETERS"),
            arguments(queryWith("startDate", "2027-01-10T00:00:00Z"), "INVALID_REQUEST_PARAMETERS"),
            arguments(queryWith("endDate", START_DATE.toString()), "VALIDATION_FAILED"),
            arguments(queryWith("endDate", START_DATE.minusDays(1).toString()), "VALIDATION_FAILED"),
            arguments(queryWith("page", "-1"), "VALIDATION_FAILED"),
            arguments(queryWith("page", "2147483648"), "INVALID_REQUEST_PARAMETERS"),
            arguments(queryWith("size", "0"), "VALIDATION_FAILED"),
            arguments(queryWith("size", "101"), "VALIDATION_FAILED"),
            arguments(queryWith("size", "1.5"), "INVALID_REQUEST_PARAMETERS"),
            arguments(queryWith("hotelId", "0"), "VALIDATION_FAILED"),
            arguments(queryWith("hotelId", "-1"), "VALIDATION_FAILED"),
            arguments(queryWith("hotelId", "9223372036854775808"), "INVALID_REQUEST_PARAMETERS"),
            arguments(queryWith("hotelId", "unknown"), "INVALID_REQUEST_PARAMETERS")
        );
    }
}
