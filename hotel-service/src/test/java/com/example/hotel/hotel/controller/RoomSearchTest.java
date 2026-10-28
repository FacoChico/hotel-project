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
    "spring.datasource.url=jdbc:h2:mem:room_search_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc(print = NONE)
class RoomSearchTest {
    private static final LocalDate START_DATE = LocalDate.of(2027, 1, 11);
    private static final LocalDate END_DATE = LocalDate.of(2027, 1, 13);
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
    @CsvSource({
        "USER, /api/v1/rooms", "ADMIN, /api/v1/rooms",
        "USER, /api/v1/rooms/recommend", "ADMIN, /api/v1/rooms/recommend"
    })
    @SneakyThrows
    void shouldReturnAvailableRoomsWhenRoleCanSearch(String role, String path) {
        final var hotel = createHotel("City Hotel");
        final var room = createRoom(hotel, "101", true, 7);

        final var result = search(path, role, query());
        final var body = mapper.readTree(result.andReturn().getResponse().getContentAsString());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(room.getId()))
            .andExpect(jsonPath("$.content[0].hotelId").value(hotel.getId()))
            .andExpect(jsonPath("$.content[0].number").value("101"))
            .andExpect(jsonPath("$.content[0].available").value(true))
            .andExpect(jsonPath("$.content[0].timesBooked").value(7));
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.get("content").get(0).size()).isEqualTo(5);
    }

    @ParameterizedTest
    @MethodSource("overlappingIntervals")
    @SneakyThrows
    void shouldExcludeRoomWhenReservedIntervalOverlaps(String path, LocalDate startDate, LocalDate endDate) {
        final var room = createRoom(createHotel("City Hotel"), "101", true, 1);
        createReservation(room, 42L, startDate, endDate, RESERVED);

        final var result = search(path, "USER", query());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest
    @MethodSource("nonBlockingReservations")
    @SneakyThrows
    void shouldIncludeRoomWhenReservationDoesNotOccupyDates(String path, RoomReservationState state,
                                                           LocalDate startDate, LocalDate endDate) {
        final var room = createRoom(createHotel("City Hotel"), "101", true, 1);
        createReservation(room, 42L, startDate, endDate, state);

        final var result = search(path, "USER", query());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(room.getId()))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms", "/api/v1/rooms/recommend"})
    @SneakyThrows
    void shouldExcludeRoomWhenOperationalAvailabilityIsDisabled(String path) {
        createRoom(createHotel("City Hotel"), "101", false, 0);

        final var result = search(path, "USER", query());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms", "/api/v1/rooms/recommend"})
    @SneakyThrows
    void shouldFilterRoomsWhenHotelIdIsProvided(String path) {
        final var selectedHotel = createHotel("City Hotel");
        final var otherHotel = createHotel("Park Hotel");
        final var expectedRoom = createRoom(selectedHotel, "101", true, 2);
        final var occupiedRoom = createRoom(selectedHotel, "102", true, 0);
        createReservation(occupiedRoom, 42L, START_DATE, END_DATE, RESERVED);
        createRoom(otherHotel, "101", true, 0);

        final var result = search(path, "USER", queryWith("hotelId", selectedHotel.getId().toString()));

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(expectedRoom.getId()))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms", "/api/v1/rooms/recommend"})
    @SneakyThrows
    void shouldSearchAllHotelsWhenHotelFilterIsAbsent(String path) {
        final var firstRoom = createRoom(createHotel("City Hotel"), "101", true, 0);
        final var secondRoom = createRoom(createHotel("Park Hotel"), "101", true, 0);

        final var result = search(path, "USER", query());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").value(firstRoom.getId()))
            .andExpect(jsonPath("$.content[1].id").value(secondRoom.getId()))
            .andExpect(jsonPath("$.totalElements").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms", "/api/v1/rooms/recommend"})
    @SneakyThrows
    void shouldReturnEmptyPageWhenHotelDoesNotExist(String path) {
        createRoom(createHotel("City Hotel"), "101", true, 0);

        final var result = search(path, "USER", queryWith("hotelId", Long.toString(Long.MAX_VALUE)));

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms", "/api/v1/rooms/recommend"})
    @SneakyThrows
    void shouldCountRoomOnceWhenSeveralNonOverlappingReservationsExist(String path) {
        final var room = createRoom(createHotel("City Hotel"), "101", true, 2);
        createReservation(room, 42L, START_DATE.minusDays(4), START_DATE, RESERVED);
        createReservation(room, 43L, END_DATE, END_DATE.plusDays(4), RESERVED);

        final var result = search(path, "USER", query());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(room.getId()))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms", "/api/v1/rooms/recommend"})
    @SneakyThrows
    void shouldPaginateFilteredRoomsWhenStableOrderingIsApplied(String path) {
        final var hotel = createHotel("City Hotel");
        createRoom(hotel, "100", false, 0);
        final var occupiedRoom = createRoom(hotel, "101", true, 0);
        createReservation(occupiedRoom, 42L, START_DATE, END_DATE, RESERVED);
        final var firstRoom = createRoom(hotel, "102", true, 5);
        final var secondRoom = createRoom(hotel, "103", true, 0);
        final var thirdRoom = createRoom(hotel, "104", true, 0);
        final var fourthRoom = createRoom(hotel, "105", true, 1);
        final var expectedIds = path.endsWith("/recommend")
            ? List.of(secondRoom.getId(), thirdRoom.getId(), fourthRoom.getId(), firstRoom.getId())
            : List.of(firstRoom.getId(), secondRoom.getId(), thirdRoom.getId(), fourthRoom.getId());

        final var firstPage = search(path, "USER", queryWith("size", "2"));
        final var secondQuery = queryWith("size", "2");
        secondQuery.put("page", "1");
        final var secondPage = search(path, "USER", secondQuery);

        firstPage.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].id").value(expectedIds.get(0)))
            .andExpect(jsonPath("$.content[1].id").value(expectedIds.get(1)))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalElements").value(4));
        secondPage.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].id").value(expectedIds.get(2)))
            .andExpect(jsonPath("$.content[1].id").value(expectedIds.get(3)))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalElements").value(4));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms", "/api/v1/rooms/recommend"})
    @SneakyThrows
    void shouldReturnEmptyPageWhenOffsetExceedsFilteredCount(String path) {
        createRoom(createHotel("City Hotel"), "101", true, 0);
        final var parameters = queryWith("page", Integer.toString(Integer.MAX_VALUE));
        parameters.put("size", "100");

        final var result = search(path, "USER", parameters);

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.page").value(Integer.MAX_VALUE))
            .andExpect(jsonPath("$.size").value(100))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms", "/api/v1/rooms/recommend"})
    @SneakyThrows
    void shouldReturnEmptyPageWhenNoRoomsExist(String path) {
        final var result = search(path, "USER", query());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms", "/api/v1/rooms/recommend"})
    @SneakyThrows
    void shouldAllowSearchWhenIntervalIsInPast(String path) {
        final var room = createRoom(createHotel("City Hotel"), "101", true, 0);
        final var parameters = Map.of("startDate", "2020-01-10", "endDate", "2020-01-12");

        final var result = search(path, "USER", parameters);

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").value(room.getId()))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms", "/api/v1/rooms/recommend"})
    @SneakyThrows
    void shouldPreserveLedgerAndCountersWhenRoomsAreSearched(String path) {
        final var hotel = createHotel("City Hotel");
        final var available = createRoom(hotel, "101", true, 5);
        final var occupied = createRoom(hotel, "102", true, 1);
        createReservation(occupied, 42L, START_DATE, END_DATE, RESERVED);

        final var result = search(path, "USER", query());

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(available.getId()));
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(roomRepository.findById(available.getId()).orElseThrow().getTimesBooked()).isEqualTo(5);
        assertThat(roomRepository.findById(occupied.getId()).orElseThrow().getTimesBooked()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("invalidQueries")
    @SneakyThrows
    void shouldRejectSearchWhenParametersAreInvalid(String path, Map<String, String> parameters, String code) {
        final var result = search(path, "USER", parameters);

        result.andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms", "/api/v1/rooms/recommend"})
    @SneakyThrows
    void shouldReturnUnauthorizedWhenUserTokenIsMissing(String path) {
        final var request = get(path);
        query().forEach(request::param);

        final var result = mockMvc.perform(request);

        result.andExpect(status().isUnauthorized())
            .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms", "/api/v1/rooms/recommend"})
    @SneakyThrows
    void shouldReturnUnauthorizedWhenTokenBelongsToService(String path) {
        final var now = Instant.now();
        final var token = sign(Map.of("sub", "booking-service", "iss", ISSUER, "aud", List.of(SERVICE_AUDIENCE),
            "iat", Date.from(now), "exp", Date.from(now.plusSeconds(120)), "scope", "reservations:write"), KEY_PAIR, RS256);
        final var request = get(path).header(AUTHORIZATION, "Bearer " + token);
        query().forEach(request::param);

        final var result = mockMvc.perform(request);

        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @SneakyThrows
    void shouldExcludeAttemptedRoomsWhenRecommendationsAreRefreshed() {
        final var hotel = createHotel("City Hotel");
        final var attempted = createRoom(hotel, "101", true, 0);
        final var next = createRoom(hotel, "102", true, 1);
        final var last = createRoom(hotel, "103", true, 2);

        final var result = search("/api/v1/rooms/recommend", "USER",
            queryWith("excludedRoomIds", attempted.getId().toString()));

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].id").value(next.getId()))
            .andExpect(jsonPath("$.content[1].id").value(last.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    @SneakyThrows
    void shouldRejectRecommendationsWhenExcludedRoomIdIsNotPositive(String roomId) {
        final var result = search("/api/v1/rooms/recommend", "USER", queryWith("excludedRoomIds", roomId));

        result.andExpect(status().isBadRequest());
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

    @SneakyThrows
    private ResultActions search(String path, String role, Map<String, String> parameters) {
        final var now = Instant.now();
        final var token = sign(Map.of("sub", "42", "iss", ISSUER, "aud", List.of(AUDIENCE), "roles", List.of(role),
            "iat", Date.from(now), "exp", Date.from(now.plusSeconds(3600))), KEY_PAIR, RS256);
        final var request = get(path).header(AUTHORIZATION, "Bearer " + token);
        parameters.forEach(request::param);
        return mockMvc.perform(request);
    }

    private static Map<String, String> query() {
        return Map.of("startDate", START_DATE.toString(), "endDate", END_DATE.toString());
    }

    private static Map<String, String> queryWith(String name, String value) {
        final var query = new LinkedHashMap<>(query());
        query.put(name, value);
        return query;
    }

    private static Stream<Arguments> overlappingIntervals() {
        return Stream.of("/api/v1/rooms", "/api/v1/rooms/recommend").flatMap(path -> Stream.of(
            arguments(path, START_DATE.minusDays(1), END_DATE.minusDays(1)),
            arguments(path, START_DATE.plusDays(1), END_DATE.plusDays(1)),
            arguments(path, START_DATE, END_DATE),
            arguments(path, START_DATE.minusDays(1), END_DATE.plusDays(1)),
            arguments(path, START_DATE, END_DATE.minusDays(1)),
            arguments(path, START_DATE.plusDays(1), END_DATE)
        ));
    }

    private static Stream<Arguments> nonBlockingReservations() {
        return Stream.of("/api/v1/rooms", "/api/v1/rooms/recommend").flatMap(path -> Stream.of(
            arguments(path, RESERVED, START_DATE.minusDays(2), START_DATE),
            arguments(path, RESERVED, END_DATE, END_DATE.plusDays(2)),
            arguments(path, RESERVED, END_DATE.plusDays(2), END_DATE.plusDays(4)),
            arguments(path, RELEASED, START_DATE, END_DATE),
            arguments(path, RELEASED, null, null)
        ));
    }

    private static Stream<Arguments> invalidQueries() {
        return Stream.of("/api/v1/rooms", "/api/v1/rooms/recommend").flatMap(path -> Stream.of(
            arguments(path, Map.of(), "INVALID_REQUEST_PARAMETERS"),
            arguments(path, Map.of("startDate", START_DATE.toString()), "INVALID_REQUEST_PARAMETERS"),
            arguments(path, Map.of("endDate", END_DATE.toString()), "INVALID_REQUEST_PARAMETERS"),
            arguments(path, queryWith("startDate", ""), "INVALID_REQUEST_PARAMETERS"),
            arguments(path, queryWith("startDate", "invalid"), "INVALID_REQUEST_PARAMETERS"),
            arguments(path, queryWith("startDate", "2027-02-30"), "INVALID_REQUEST_PARAMETERS"),
            arguments(path, queryWith("startDate", "2027-01-11T00:00:00Z"), "INVALID_REQUEST_PARAMETERS"),
            arguments(path, queryWith("startDate", "2027-01-11Z"), "INVALID_REQUEST_PARAMETERS"),
            arguments(path, queryWith("endDate", START_DATE.toString()), "VALIDATION_FAILED"),
            arguments(path, queryWith("endDate", START_DATE.minusDays(1).toString()), "VALIDATION_FAILED"),
            arguments(path, queryWith("page", "-1"), "VALIDATION_FAILED"),
            arguments(path, queryWith("page", "2147483648"), "INVALID_REQUEST_PARAMETERS"),
            arguments(path, queryWith("size", "0"), "VALIDATION_FAILED"),
            arguments(path, queryWith("size", "101"), "VALIDATION_FAILED"),
            arguments(path, queryWith("size", "1.5"), "INVALID_REQUEST_PARAMETERS"),
            arguments(path, queryWith("hotelId", "0"), "VALIDATION_FAILED"),
            arguments(path, queryWith("hotelId", "-1"), "VALIDATION_FAILED"),
            arguments(path, queryWith("hotelId", "9223372036854775808"), "INVALID_REQUEST_PARAMETERS"),
            arguments(path, queryWith("hotelId", "unknown"), "INVALID_REQUEST_PARAMETERS")
        ));
    }
}
