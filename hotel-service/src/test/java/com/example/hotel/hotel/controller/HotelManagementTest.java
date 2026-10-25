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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    "spring.datasource.url=jdbc:h2:mem:hotel_management_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc(print = NONE)
class HotelManagementTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private JsonMapper mapper;

    @DynamicPropertySource
    private static void registerJwtProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @BeforeEach
    private void beforeEach() {
        roomRepository.deleteAll();
        hotelRepository.deleteAll();
    }

    @ParameterizedTest
    @MethodSource("validHotelDetails")
    @SneakyThrows
    void shouldCreateHotelWhenAdminProvidesValidDetails(String name, String address) {
        final var payload = Map.of("name", name, "address", address);

        final var result = mockMvc.perform(authenticatedRequest(POST, "/api/v1/hotels", "ADMIN", payload))
            .andExpect(status().isCreated())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
            .andReturn();
        final var response = mapper.readTree(result.getResponse().getContentAsString());
        final var persisted = hotelRepository.findById(response.get("id").longValue()).orElseThrow();

        assertThat(response.size()).isEqualTo(3);
        assertThat(persisted.getId()).isPositive();
        assertThat(response.get("name").stringValue()).isEqualTo(name);
        assertThat(response.get("address").stringValue()).isEqualTo(address);
        assertThat(persisted.getName()).isEqualTo(name);
        assertThat(persisted.getAddress()).isEqualTo(address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    @SneakyThrows
    void shouldReturnHotelWhenRoleCanReadExistingHotel(String role) {
        final var hotel = persistHotel("City Hotel");

        final var result = mockMvc.perform(authenticatedRequest(GET, "/api/v1/hotels/" + hotel.getId(), role, null));

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(hotel.getId()))
            .andExpect(jsonPath("$.name").value("City Hotel"))
            .andExpect(jsonPath("$.address").value("Central Street, 15"))
            .andExpect(jsonPath("$.rooms").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    @SneakyThrows
    void shouldReturnOrderedPagesWhenHotelsExist(String role) {
        final var first = persistHotel("City Hotel");
        final var second = persistHotel("Park Hotel");
        final var third = persistHotel("River Hotel");

        final var firstPage = mockMvc.perform(authenticatedRequest(GET, "/api/v1/hotels?page=0&size=2", role, null));
        final var secondPage = mockMvc.perform(authenticatedRequest(GET, "/api/v1/hotels?page=1&size=2", role, null));

        firstPage.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].id").value(first.getId()))
            .andExpect(jsonPath("$.content[1].id").value(second.getId()))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalElements").value(3));
        secondPage.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(third.getId()))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    @SneakyThrows
    void shouldReturnEmptyDefaultPageWhenNoHotelsExist() {
        final var hotelRequest = authenticatedRequest(GET, "/api/v1/hotels", "USER", null);

        final var result = mockMvc.perform(hotelRequest);

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=2&size=1", "?page=2147483647&size=100"})
    @SneakyThrows
    void shouldReturnEmptyPageWhenOffsetExceedsHotelCount(String query) {
        persistHotel("City Hotel");

        final var result = mockMvc.perform(authenticatedRequest(GET, "/api/v1/hotels" + query, "USER", null));

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @CsvSource({"name, Park Hotel", "address, Park Street 21"})
    @SneakyThrows
    void shouldPreserveMissingFieldWhenAdminUpdatesOneHotelField(String field, String value) {
        final var hotel = persistHotel("City Hotel");

        final var result = mockMvc.perform(authenticatedRequest(PATCH, "/api/v1/hotels/" + hotel.getId(),
            "ADMIN", Map.of(field, value)));
        final var persisted = hotelRepository.findById(hotel.getId()).orElseThrow();

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(hotel.getId()))
            .andExpect(jsonPath("$." + field).value(value));
        assertThat(persisted.getName()).isEqualTo(field.equals("name")
            ? value
            : "City Hotel");
        assertThat(persisted.getAddress()).isEqualTo(field.equals("address")
            ? value
            : "Central Street, 15");
    }

    @Test
    @SneakyThrows
    void shouldUpdateAllFieldsWhenAdminProvidesMaximumLengths() {
        final var hotel = persistHotel("City Hotel");
        final var payload = Map.of("name", "N".repeat(200), "address", "A".repeat(500));

        final var result = mockMvc.perform(authenticatedRequest(PATCH, "/api/v1/hotels/" + hotel.getId(), "ADMIN", payload));
        final var persisted = hotelRepository.findById(hotel.getId()).orElseThrow();

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value(payload.get("name")))
            .andExpect(jsonPath("$.address").value(payload.get("address")));
        assertThat(persisted.getName()).isEqualTo(payload.get("name"));
        assertThat(persisted.getAddress()).isEqualTo(payload.get("address"));
    }

    @Test
    @SneakyThrows
    void shouldDeleteHotelWhenNoRoomsExist() {
        final var hotel = persistHotel("City Hotel");
        final var otherHotel = persistHotel("Park Hotel");

        final var result = mockMvc.perform(authenticatedRequest(DELETE, "/api/v1/hotels/" + hotel.getId(), "ADMIN", null));

        result.andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(hotelRepository.findById(hotel.getId())).isEmpty();
        assertThat(hotelRepository.findById(otherHotel.getId())).isPresent();
        mockMvc.perform(authenticatedRequest(GET, "/api/v1/hotels/" + hotel.getId(), "USER", null))
            .andExpect(status().isNotFound());
        mockMvc.perform(authenticatedRequest(DELETE, "/api/v1/hotels/" + hotel.getId(), "ADMIN", null))
            .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @SneakyThrows
    void shouldReturnConflictWhenHotelStillHasRooms(boolean available) {
        final var hotel = persistHotel("City Hotel");
        final var room = roomRepository.saveAndFlush(new Room(hotel, "101", available));

        final var result = mockMvc.perform(authenticatedRequest(DELETE, "/api/v1/hotels/" + hotel.getId(), "ADMIN", null));

        result.andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("HOTEL_HAS_ROOMS"))
            .andExpect(jsonPath("$.detail").value("Hotel still has rooms"))
            .andExpect(jsonPath("$.instance").value("/api/v1/hotels/" + hotel.getId()));
        assertThat(hotelRepository.findById(hotel.getId())).isPresent();
        assertThat(roomRepository.findById(room.getId())).isPresent();
    }

    @Test
    @SneakyThrows
    void shouldDeleteHotelWhenLastRoomHasBeenRemoved() {
        final var hotel = persistHotel("City Hotel");
        final var room = roomRepository.saveAndFlush(new Room(hotel, "101", true));
        roomRepository.deleteById(room.getId());

        final var result = mockMvc.perform(authenticatedRequest(DELETE, "/api/v1/hotels/" + hotel.getId(), "ADMIN", null));

        result.andExpect(status().isNoContent());
        assertThat(hotelRepository.findById(hotel.getId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PATCH", "DELETE"})
    @SneakyThrows
    void shouldReturnNotFoundWhenHotelDoesNotExist(String method) {
        final var payload = method.equals("PATCH")
            ? Map.of("name", "City Hotel")
            : null;

        final var result = mockMvc.perform(authenticatedRequest(HttpMethod.valueOf(method),
            "/api/v1/hotels/9223372036854775807", "ADMIN", payload));

        result.andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("HOTEL_NOT_FOUND"));
    }

    @ParameterizedTest
    @MethodSource("invalidHotelDetails")
    @SneakyThrows
    void shouldRejectCreationWhenDetailsAreInvalid(String scenario, Object name, Object address, String code) {
        final var payload = new LinkedHashMap<String, Object>();
        payload.put("name", name);
        payload.put("address", address);

        final var result = mockMvc.perform(authenticatedRequest(POST, "/api/v1/hotels", "ADMIN", payload));

        result.andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value(code));
        assertThat(hotelRepository.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidPatchBodies")
    @SneakyThrows
    void shouldPreserveHotelWhenPatchIsInvalid(String payload, String code) {
        final var hotel = persistHotel("City Hotel");
        final var hotelRequest = authenticatedRequest(PATCH, "/api/v1/hotels/" + hotel.getId(), "ADMIN", null)
            .contentType(APPLICATION_JSON).content(payload);

        final var result = mockMvc.perform(hotelRequest);
        final var unchanged = hotelRepository.findById(hotel.getId()).orElseThrow();

        result.andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value(code));
        assertThat(unchanged.getName()).isEqualTo("City Hotel");
        assertThat(unchanged.getAddress()).isEqualTo("Central Street, 15");
    }

    @Test
    @SneakyThrows
    void shouldRejectUpdateWhenNoFieldsAreProvided() {
        final var hotel = persistHotel("City Hotel");

        final var result = mockMvc.perform(authenticatedRequest(PATCH, "/api/v1/hotels/" + hotel.getId(), "ADMIN", Map.of()));

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.detail").value("At least one hotel field is required"));
        assertThat(hotelRepository.findById(hotel.getId()).orElseThrow().getName()).isEqualTo("City Hotel");
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /api/v1/hotels",
        "GET, /api/v1/hotels/1",
        "POST, /api/v1/hotels",
        "PATCH, /api/v1/hotels/1",
        "DELETE, /api/v1/hotels/1"
    })
    @SneakyThrows
    void shouldReturnUnauthorizedWhenTokenIsMissing(String method, String path) {
        final var result = mockMvc.perform(request(HttpMethod.valueOf(method), URI.create(path)));

        result.andExpect(status().isUnauthorized())
            .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(hotelRepository.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "POST, /api/v1/hotels",
        "PATCH, /api/v1/hotels/1",
        "DELETE, /api/v1/hotels/1"
    })
    @SneakyThrows
    void shouldDenyChangesWhenUserHasNoAdminRole(String method, String path) {
        final var hotelRequest = authenticatedRequest(HttpMethod.valueOf(method), path, "USER",
            Map.of("name", "City Hotel", "address", "Central Street, 15"));

        final var result = mockMvc.perform(hotelRequest);

        result.andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(hotelRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=101", "?page=abc", "?page=2147483648"})
    @SneakyThrows
    void shouldRejectListWhenPaginationIsInvalid(String query) {
        final var result = mockMvc.perform(authenticatedRequest(GET, "/api/v1/hotels" + query, "USER", null));

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").exists());
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
    void shouldReturnNotFoundWhenHotelPathIdIsNotNumeric(String method, String id) {
        final var payload = method.equals("PATCH")
            ? Map.of("name", "City Hotel")
            : null;

        final var result = mockMvc.perform(authenticatedRequest(HttpMethod.valueOf(method),
            "/api/v1/hotels/" + id, "ADMIN", payload));

        result.andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @MethodSource("invalidHotelIdentifiers")
    @SneakyThrows
    void shouldRejectRequestWhenHotelIdIsInvalid(String method, String id) {
        final var payload = method.equals("PATCH")
            ? Map.of("name", "City Hotel")
            : null;

        final var result = mockMvc.perform(authenticatedRequest(HttpMethod.valueOf(method),
            "/api/v1/hotels/" + id, "ADMIN", payload));

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "null",
        "{",
        "{\"name\":\"City Hotel\",\"address\":\"Central Street\",\"id\":1}"
    })
    @SneakyThrows
    void shouldRejectCreationWhenBodyIsUnsupported(String payload) {
        final var hotelRequest = authenticatedRequest(POST, "/api/v1/hotels", "ADMIN", null)
            .contentType(APPLICATION_JSON).content(payload);

        final var result = mockMvc.perform(hotelRequest);

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").exists());
        assertThat(hotelRepository.count()).isZero();
    }

    @Test
    @SneakyThrows
    void shouldReturnSafeViolationsWhenHotelFieldsAreInvalid() {
        final var payload = Map.of("name", "N".repeat(201), "address", " ");

        final var result = mockMvc.perform(authenticatedRequest(POST, "/api/v1/hotels", "ADMIN", payload));

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.violations[0].field").value("address"))
            .andExpect(jsonPath("$.violations[1].field").value("name"))
            .andExpect(jsonPath("$.violations[*].rejectedValue").isEmpty());
    }

    private Hotel persistHotel(String name) {
        return hotelRepository.saveAndFlush(new Hotel(name, "Central Street, 15"));
    }

    private MockHttpServletRequestBuilder authenticatedRequest(HttpMethod method, String path, String role, Object payload) {
        final var hotelRequest = request(method, URI.create(path))
            .header(AUTHORIZATION, "Bearer " + userToken(role));
        if (payload != null) {
            hotelRequest.contentType(APPLICATION_JSON).content(mapper.writeValueAsBytes(payload));
        }
        return hotelRequest;
    }

    private static String userToken(String role) {
        final var now = Instant.now();
        return sign(Map.of(
            "sub", "42", "iss", ISSUER, "aud", List.of(AUDIENCE), "roles", List.of(role),
            "iat", Date.from(now), "exp", Date.from(now.plusSeconds(3600))), KEY_PAIR, RS256);
    }

    private static Stream<Arguments> validHotelDetails() {
        return Stream.of(
            arguments("City Hotel", "Central Street, 15"),
            arguments("H", "A"),
            arguments("N".repeat(200), "A".repeat(500)),
            arguments(" City Hotel ", " Central Street, 15 "));
    }

    private static Stream<Arguments> invalidHotelDetails() {
        return Stream.of(
            arguments("missing name", null, "Central Street, 15", "VALIDATION_FAILED"),
            arguments("missing address", "City Hotel", null, "VALIDATION_FAILED"),
            arguments("empty name", "", "Central Street, 15", "VALIDATION_FAILED"),
            arguments("blank name", " \t\n", "Central Street, 15", "VALIDATION_FAILED"),
            arguments("empty address", "City Hotel", "", "VALIDATION_FAILED"),
            arguments("blank address", "City Hotel", " \t\n", "VALIDATION_FAILED"),
            arguments("long name", "N".repeat(201), "Central Street, 15", "VALIDATION_FAILED"),
            arguments("long address", "City Hotel", "A".repeat(501), "VALIDATION_FAILED"),
            arguments("numeric name", 123, "Central Street, 15", "INVALID_REQUEST_BODY"),
            arguments("boolean address", "City Hotel", true, "INVALID_REQUEST_BODY"));
    }

    private static Stream<Arguments> invalidPatchBodies() {
        return Stream.of(
            arguments("{\"name\":null}", "INVALID_REQUEST_BODY"),
            arguments("{\"address\":null}", "INVALID_REQUEST_BODY"),
            arguments("{\"name\":\"Park Hotel\",\"address\":null}", "INVALID_REQUEST_BODY"),
            arguments("{\"name\":\"\"}", "VALIDATION_FAILED"),
            arguments("{\"address\":\" \"}", "VALIDATION_FAILED"),
            arguments("{\"name\":\"\\u2003\"}", "VALIDATION_FAILED"),
            arguments("{\"name\":123}", "INVALID_REQUEST_BODY"),
            arguments("{\"address\":false}", "INVALID_REQUEST_BODY"),
            arguments("{\"name\":\"Park Hotel\",\"rooms\":[]}", "INVALID_REQUEST_BODY"),
            arguments("{\"name\":\"Park Hotel\",\"id\":1}", "INVALID_REQUEST_BODY"),
            arguments("{\"name\":\"" + "N".repeat(201) + "\"}", "VALIDATION_FAILED"),
            arguments("{\"address\":\"" + "A".repeat(501) + "\"}", "VALIDATION_FAILED"),
            arguments("null", "INVALID_REQUEST_BODY"),
            arguments("{", "INVALID_REQUEST_BODY"));
    }

    private static Stream<Arguments> invalidHotelIdentifiers() {
        return Stream.of("GET", "PATCH", "DELETE")
            .flatMap(method -> Stream.of("0", "9223372036854775808")
                .map(id -> arguments(method, id)));
    }
}
