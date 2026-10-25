package com.example.hotel.hotel.controller;

import com.example.hotel.hotel.domain.Hotel;
import com.example.hotel.hotel.domain.Room;
import com.example.hotel.hotel.repository.HotelRepository;
import com.example.hotel.hotel.repository.RoomRepository;
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
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import lombok.SneakyThrows;
import tools.jackson.databind.json.JsonMapper;

import static com.example.hotel.hotel.testutil.JwtTestKeys.AUDIENCE;
import static com.example.hotel.hotel.testutil.JwtTestKeys.ISSUER;
import static com.example.hotel.hotel.testutil.JwtTestKeys.KEY_PAIR;
import static com.example.hotel.hotel.testutil.JwtTestKeys.sign;
import static com.nimbusds.jose.JWSAlgorithm.RS256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:room_management_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc(print = NONE)
class RoomManagementTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper mapper;

    @DynamicPropertySource
    private static void registerJwtProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @BeforeEach
    public void beforeEach() {
        roomRepository.deleteAll();
        hotelRepository.deleteAll();
    }

    @ParameterizedTest
    @MethodSource("validRoomDetails")
    @SneakyThrows
    void shouldCreateRoomWhenAdminProvidesValidDetails(String number, boolean available) {
        final var hotel = persistHotel("City Hotel");
        final var payload = Map.of("hotelId", hotel.getId(), "number", number, "available", available);

        final var result = mockMvc.perform(authenticatedRequest(POST, "/api/v1/rooms", "ADMIN", payload))
            .andExpect(status().isCreated()).andReturn();
        final var response = mapper.readTree(result.getResponse().getContentAsString());
        final var persisted = roomRepository.findById(response.get("id").longValue()).orElseThrow();

        assertThat(response.size()).isEqualTo(5);
        assertThat(persisted.getId()).isPositive();
        assertThat(response.get("hotelId").longValue()).isEqualTo(hotel.getId());
        assertThat(response.get("number").stringValue()).isEqualTo(number);
        assertThat(response.get("available").booleanValue()).isEqualTo(available);
        assertThat(response.get("timesBooked").longValue()).isZero();
        assertThat(persisted.getHotel().getId()).isEqualTo(hotel.getId());
        assertThat(persisted.getNumber()).isEqualTo(number);
        assertThat(persisted.isAvailable()).isEqualTo(available);
        assertThat(persisted.getTimesBooked()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    @SneakyThrows
    void shouldReturnRoomWhenRoleCanReadExistingRoom(String role) {
        final var hotel = persistHotel("City Hotel");
        final var room = persistRoom(hotel, "101", false);
        jdbcTemplate.update("UPDATE rooms SET times_booked = 7 WHERE id = ?", room.getId());

        final var result = mockMvc.perform(authenticatedRequest(GET, "/api/v1/rooms/" + room.getId(), role, null));

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(room.getId()))
            .andExpect(jsonPath("$.hotelId").value(hotel.getId()))
            .andExpect(jsonPath("$.number").value("101"))
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.timesBooked").value(7))
            .andExpect(jsonPath("$.hotel").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @SneakyThrows
    void shouldPreserveNumberWhenAdminChangesAvailability(boolean available) {
        final var hotel = persistHotel("City Hotel");
        final var room = persistRoom(hotel, "101", !available);

        final var result = mockMvc.perform(authenticatedRequest(PATCH, "/api/v1/rooms/" + room.getId(),
            "ADMIN", Map.of("available", available)));
        final var persisted = roomRepository.findById(room.getId()).orElseThrow();

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.number").value("101"))
            .andExpect(jsonPath("$.available").value(available));
        assertThat(persisted.getNumber()).isEqualTo("101");
        assertThat(persisted.isAvailable()).isEqualTo(available);
        assertThat(persisted.getHotel().getId()).isEqualTo(hotel.getId());
    }

    @Test
    @SneakyThrows
    void shouldPreserveAvailabilityWhenAdminChangesNumber() {
        final var hotel = persistHotel("City Hotel");
        final var room = persistRoom(hotel, "101", false);

        final var result = mockMvc.perform(authenticatedRequest(PATCH, "/api/v1/rooms/" + room.getId(),
            "ADMIN", Map.of("number", "N".repeat(32))));
        final var persisted = roomRepository.findById(room.getId()).orElseThrow();

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.number").value("N".repeat(32)))
            .andExpect(jsonPath("$.available").value(false));
        assertThat(persisted.getNumber()).isEqualTo("N".repeat(32));
        assertThat(persisted.isAvailable()).isFalse();
    }

    @Test
    @SneakyThrows
    void shouldPreserveCounterWhenAdminUpdatesRoomDetails() {
        final var hotel = persistHotel("City Hotel");
        final var room = persistRoom(hotel, "101", true);
        jdbcTemplate.update("UPDATE rooms SET times_booked = 7 WHERE id = ?", room.getId());

        final var result = mockMvc.perform(authenticatedRequest(PATCH, "/api/v1/rooms/" + room.getId(),
            "ADMIN", Map.of("number", "102", "available", false)));
        final var persisted = roomRepository.findById(room.getId()).orElseThrow();

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.number").value("102"))
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.timesBooked").value(7));
        assertThat(persisted.getNumber()).isEqualTo("102");
        assertThat(persisted.isAvailable()).isFalse();
        assertThat(persisted.getTimesBooked()).isEqualTo(7);
        assertThat(persisted.getHotel().getId()).isEqualTo(hotel.getId());
    }

    @Test
    @SneakyThrows
    void shouldRejectCreationWhenRoomNumberAlreadyExistsInHotel() {
        final var hotel = persistHotel("City Hotel");
        persistRoom(hotel, "101", true);
        final var payload = Map.of("hotelId", hotel.getId(), "number", "101", "available", false);

        final var result = mockMvc.perform(authenticatedRequest(POST, "/api/v1/rooms", "ADMIN", payload));

        result.andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ROOM_NUMBER_ALREADY_EXISTS"));
        assertThat(roomRepository.count()).isEqualTo(1);
    }

    @Test
    @SneakyThrows
    void shouldAllowSameNumberWhenRoomsBelongToDifferentHotels() {
        final var firstHotel = persistHotel("City Hotel");
        final var secondHotel = persistHotel("Park Hotel");
        persistRoom(firstHotel, "101", true);
        final var payload = Map.of("hotelId", secondHotel.getId(), "number", "101", "available", true);

        final var result = mockMvc.perform(authenticatedRequest(POST, "/api/v1/rooms", "ADMIN", payload));

        result.andExpect(status().isCreated())
            .andExpect(jsonPath("$.hotelId").value(secondHotel.getId()));
        assertThat(roomRepository.count()).isEqualTo(2);
    }

    @Test
    @SneakyThrows
    void shouldRollBackAllChangesWhenUpdatedNumberConflicts() {
        final var hotel = persistHotel("City Hotel");
        persistRoom(hotel, "101", true);
        final var room = persistRoom(hotel, "102", true);

        final var result = mockMvc.perform(authenticatedRequest(PATCH, "/api/v1/rooms/" + room.getId(),
            "ADMIN", Map.of("number", "101", "available", false)));
        final var unchanged = roomRepository.findById(room.getId()).orElseThrow();

        result.andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ROOM_NUMBER_ALREADY_EXISTS"));
        assertThat(unchanged.getNumber()).isEqualTo("102");
        assertThat(unchanged.isAvailable()).isTrue();
        assertThat(unchanged.getTimesBooked()).isZero();
        assertThat(roomRepository.count()).isEqualTo(2);
    }

    @Test
    @SneakyThrows
    void shouldReturnNotFoundWhenOwningHotelDoesNotExist() {
        final var payload = Map.of("hotelId", Long.MAX_VALUE, "number", "101", "available", true);

        final var result = mockMvc.perform(authenticatedRequest(POST, "/api/v1/rooms", "ADMIN", payload));

        result.andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("HOTEL_NOT_FOUND"));
        assertThat(roomRepository.count()).isZero();
    }

    @Test
    @SneakyThrows
    void shouldDeleteRoomWhenAdminRequestsDeletion() {
        final var hotel = persistHotel("City Hotel");
        final var room = persistRoom(hotel, "101", true);
        final var otherRoom = persistRoom(hotel, "102", true);

        final var result = mockMvc.perform(authenticatedRequest(DELETE, "/api/v1/rooms/" + room.getId(), "ADMIN", null));

        result.andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(roomRepository.findById(room.getId())).isEmpty();
        assertThat(roomRepository.findById(otherRoom.getId())).isPresent();
        assertThat(hotelRepository.findById(hotel.getId())).isPresent();
        mockMvc.perform(authenticatedRequest(GET, "/api/v1/rooms/" + room.getId(), "USER", null))
            .andExpect(status().isNotFound());
        mockMvc.perform(authenticatedRequest(DELETE, "/api/v1/rooms/" + room.getId(), "ADMIN", null))
            .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PATCH", "DELETE"})
    @SneakyThrows
    void shouldReturnNotFoundWhenRoomDoesNotExist(String method) {
        final var payload = method.equals("PATCH")
            ? Map.of("number", "101")
            : null;

        final var result = mockMvc.perform(authenticatedRequest(HttpMethod.valueOf(method),
            "/api/v1/rooms/9223372036854775807", "ADMIN", payload));

        result.andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }

    @ParameterizedTest
    @MethodSource("invalidRoomDetails")
    @SneakyThrows
    void shouldRejectCreationWhenDetailsAreInvalid(String scenario, Object number, Object available, String code) {
        final var hotel = persistHotel("City Hotel");
        final var payload = new LinkedHashMap<String, Object>();
        payload.put("hotelId", hotel.getId());
        payload.put("number", number);
        payload.put("available", available);

        final var result = mockMvc.perform(authenticatedRequest(POST, "/api/v1/rooms", "ADMIN", payload));

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(code));
        assertThat(roomRepository.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidHotelIds")
    @SneakyThrows
    void shouldRejectCreationWhenHotelIdIsInvalid(String scenario, Object hotelId, String code) {
        final var payload = new LinkedHashMap<String, Object>();
        payload.put("hotelId", hotelId);
        payload.put("number", "101");
        payload.put("available", true);

        final var result = mockMvc.perform(authenticatedRequest(POST, "/api/v1/rooms", "ADMIN", payload));

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(code));
        assertThat(roomRepository.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidPatchBodies")
    @SneakyThrows
    void shouldPreserveRoomWhenPatchIsInvalid(String payload, String code) {
        final var hotel = persistHotel("City Hotel");
        final var room = persistRoom(hotel, "101", true);
        final var roomRequest = authenticatedRequest(PATCH, "/api/v1/rooms/" + room.getId(), "ADMIN", null)
            .contentType(APPLICATION_JSON).content(payload);

        final var result = mockMvc.perform(roomRequest);
        final var unchanged = roomRepository.findById(room.getId()).orElseThrow();

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(code));
        assertThat(unchanged.getNumber()).isEqualTo("101");
        assertThat(unchanged.isAvailable()).isTrue();
        assertThat(unchanged.getHotel().getId()).isEqualTo(hotel.getId());
        assertThat(unchanged.getTimesBooked()).isZero();
    }

    @Test
    @SneakyThrows
    void shouldRejectUpdateWhenNoFieldsAreProvided() {
        final var hotel = persistHotel("City Hotel");
        final var room = persistRoom(hotel, "101", true);

        final var result = mockMvc.perform(authenticatedRequest(PATCH, "/api/v1/rooms/" + room.getId(), "ADMIN", Map.of()));

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.detail").value("At least one room field is required"));
        assertThat(roomRepository.findById(room.getId()).orElseThrow().getNumber()).isEqualTo("101");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hotelId", "number", "available"})
    @SneakyThrows
    void shouldRejectCreationWhenRequiredFieldIsMissing(String field) {
        final var hotel = persistHotel("City Hotel");
        final var payload = new LinkedHashMap<String, Object>(Map.of(
            "hotelId", hotel.getId(), "number", "101", "available", true));
        payload.remove(field);

        final var result = mockMvc.perform(authenticatedRequest(POST, "/api/v1/rooms", "ADMIN", payload));

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(roomRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"timesBooked", "id", "hotel"})
    @SneakyThrows
    void shouldRejectCreationWhenFieldsAreNotSupported(String field) {
        final var hotel = persistHotel("City Hotel");
        final var payload = new LinkedHashMap<String, Object>(Map.of(
            "hotelId", hotel.getId(), "number", "101", "available", true));
        payload.put(field, 7);

        final var result = mockMvc.perform(authenticatedRequest(POST, "/api/v1/rooms", "ADMIN", payload));

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        assertThat(roomRepository.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /api/v1/rooms/1",
        "POST, /api/v1/rooms",
        "PATCH, /api/v1/rooms/1",
        "DELETE, /api/v1/rooms/1"
    })
    @SneakyThrows
    void shouldReturnUnauthorizedWhenTokenIsMissing(String method, String path) {
        final var result = mockMvc.perform(request(HttpMethod.valueOf(method), URI.create(path)));

        result.andExpect(status().isUnauthorized())
            .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(roomRepository.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "POST, /api/v1/rooms",
        "PATCH, /api/v1/rooms/1",
        "DELETE, /api/v1/rooms/1"
    })
    @SneakyThrows
    void shouldDenyChangesWhenUserHasNoAdminRole(String method, String path) {
        final var roomRequest = authenticatedRequest(HttpMethod.valueOf(method), path, "USER", Map.of("number", "101"));

        final var result = mockMvc.perform(roomRequest);

        result.andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(roomRepository.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "GET, invalid",
        "PATCH, invalid",
        "DELETE, invalid",
        "GET, -1",
        "PATCH, -1",
        "DELETE, -1"
    })
    @SneakyThrows
    void shouldReturnNotFoundWhenRoomPathIdIsNotNumeric(String method, String id) {
        final var payload = method.equals("PATCH")
            ? Map.of("number", "101")
            : null;

        final var result = mockMvc.perform(authenticatedRequest(HttpMethod.valueOf(method),
            "/api/v1/rooms/" + id, "ADMIN", payload));

        result.andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @MethodSource("invalidRoomIds")
    @SneakyThrows
    void shouldRejectRequestWhenRoomIdIsInvalid(String method, String id, String code) {
        final var payload = method.equals("PATCH")
            ? Map.of("number", "101")
            : null;

        final var result = mockMvc.perform(authenticatedRequest(HttpMethod.valueOf(method),
            "/api/v1/rooms/" + id, "ADMIN", payload));

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    @SneakyThrows
    void shouldCreateSingleRoomWhenSameNumberIsSubmittedConcurrently() {
        // given
        final var hotel = persistHotel("City Hotel");
        final var payload = Map.of("hotelId", hotel.getId(), "number", "101", "available", true);
        final var ready = new CountDownLatch(2);
        final var start = new CountDownLatch(1);

        try (final var executor = Executors.newFixedThreadPool(2)) {
            final Callable<Integer> createRoom = () -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(authenticatedRequest(POST, "/api/v1/rooms", "ADMIN", payload))
                    .andReturn().getResponse().getStatus();
            };
            final var first = executor.submit(createRoom);
            final var second = executor.submit(createRoom);

            // when
            ready.await();
            start.countDown();
            final var statuses = List.of(first.get(), second.get());

            // then
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(roomRepository.count()).isEqualTo(1);
        }
    }

    private Hotel persistHotel(String name) {
        return hotelRepository.saveAndFlush(new Hotel(name, "Central Street, 15"));
    }

    private Room persistRoom(Hotel hotel, String number, boolean available) {
        return roomRepository.saveAndFlush(new Room(hotel, number, available));
    }

    private MockHttpServletRequestBuilder authenticatedRequest(HttpMethod method, String path, String role, Object payload) {
        final var roomRequest = request(method, URI.create(path))
            .header(AUTHORIZATION, "Bearer " + userToken(role));
        if (payload != null) {
            roomRequest.contentType(APPLICATION_JSON).content(mapper.writeValueAsBytes(payload));
        }
        return roomRequest;
    }

    private static String userToken(String role) {
        final var now = Instant.now();
        return sign(Map.of(
            "sub", "42", "iss", ISSUER, "aud", List.of(AUDIENCE), "roles", List.of(role),
            "iat", Date.from(now), "exp", Date.from(now.plusSeconds(3600))), KEY_PAIR, RS256);
    }

    private static Stream<Arguments> validRoomDetails() {
        return Stream.of(
            arguments("101 A", true),
            arguments("101 A", false),
            arguments("R", true),
            arguments("N".repeat(32), true),
            arguments(" 101 A ", false));
    }

    private static Stream<Arguments> invalidRoomDetails() {
        return Stream.of(
            arguments("missing number", null, true, "VALIDATION_FAILED"),
            arguments("empty number", "", true, "VALIDATION_FAILED"),
            arguments("blank number", " \t\n", true, "VALIDATION_FAILED"),
            arguments("long number", "N".repeat(33), true, "VALIDATION_FAILED"),
            arguments("numeric number", 101, true, "INVALID_REQUEST_BODY"),
            arguments("missing availability", "101", null, "VALIDATION_FAILED"),
            arguments("numeric availability", "101", 1, "INVALID_REQUEST_BODY"),
            arguments("string availability", "101", "true", "INVALID_REQUEST_BODY"),
            arguments("empty availability", "101", "", "INVALID_REQUEST_BODY"));
    }

    private static Stream<Arguments> invalidHotelIds() {
        return Stream.of(
            arguments("missing hotel ID", null, "VALIDATION_FAILED"),
            arguments("zero hotel ID", 0, "VALIDATION_FAILED"),
            arguments("negative hotel ID", -1, "VALIDATION_FAILED"),
            arguments("fractional hotel ID", 1.5, "INVALID_REQUEST_BODY"),
            arguments("boolean hotel ID", true, "INVALID_REQUEST_BODY"),
            arguments("string hotel ID", "1", "INVALID_REQUEST_BODY"),
            arguments("empty hotel ID", "", "INVALID_REQUEST_BODY"),
            arguments("overflow hotel ID", new BigInteger("9223372036854775808"), "INVALID_REQUEST_BODY"));
    }

    private static Stream<Arguments> invalidPatchBodies() {
        return Stream.of(
            arguments("{\"number\":null}", "INVALID_REQUEST_BODY"),
            arguments("{\"available\":null}", "INVALID_REQUEST_BODY"),
            arguments("{\"number\":\"102\",\"available\":null}", "INVALID_REQUEST_BODY"),
            arguments("{\"number\":\"\"}", "VALIDATION_FAILED"),
            arguments("{\"number\":\" \\t\"}", "VALIDATION_FAILED"),
            arguments("{\"number\":\"\\u2003\"}", "VALIDATION_FAILED"),
            arguments("{\"number\":123}", "INVALID_REQUEST_BODY"),
            arguments("{\"number\":\"" + "N".repeat(33) + "\"}", "VALIDATION_FAILED"),
            arguments("{\"available\":1}", "INVALID_REQUEST_BODY"),
            arguments("{\"available\":\"false\"}", "INVALID_REQUEST_BODY"),
            arguments("{\"number\":\"102\",\"available\":\"\"}", "INVALID_REQUEST_BODY"),
            arguments("{\"number\":\"102\",\"hotelId\":1}", "INVALID_REQUEST_BODY"),
            arguments("{\"number\":\"102\",\"timesBooked\":7}", "INVALID_REQUEST_BODY"),
            arguments("{\"number\":\"102\",\"id\":1}", "INVALID_REQUEST_BODY"),
            arguments("null", "INVALID_REQUEST_BODY"),
            arguments("{", "INVALID_REQUEST_BODY"));
    }

    private static Stream<Arguments> invalidRoomIds() {
        return Stream.of("GET", "PATCH", "DELETE")
            .flatMap(method -> Stream.of(
                arguments(method, "0", "VALIDATION_FAILED"),
                arguments(method, "9223372036854775808", "INVALID_REQUEST_PARAMETERS")));
    }
}
