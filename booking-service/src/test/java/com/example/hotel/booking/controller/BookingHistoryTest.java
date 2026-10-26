package com.example.hotel.booking.controller;

import com.example.hotel.booking.domain.Booking;
import com.example.hotel.booking.domain.BookingStatus;
import com.example.hotel.booking.domain.User;
import com.example.hotel.booking.gateway.HotelServiceGateway;
import com.example.hotel.booking.repository.BookingRepository;
import com.example.hotel.booking.repository.UserRepository;
import com.example.hotel.booking.security.InternalTokenService;
import com.example.hotel.booking.security.JwtTokenService;
import com.example.hotel.booking.testutil.JwtTestKeys;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;

import lombok.SneakyThrows;
import tools.jackson.databind.json.JsonMapper;

import static com.example.hotel.booking.domain.BookingStatus.CANCELLED;
import static com.example.hotel.booking.domain.BookingStatus.CONFIRMED;
import static com.example.hotel.booking.domain.BookingStatus.PENDING;
import static com.example.hotel.booking.domain.UserRole.ADMIN;
import static com.example.hotel.booking.domain.UserRole.USER;
import static java.time.ZoneOffset.UTC;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "security.bootstrap-admin.enabled=false",
    "booking.recovery.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:booking_history_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc(print = NONE)
class BookingHistoryTest {
    private static final Instant CREATED_AT = Instant.parse("2027-01-10T12:00:00Z");
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenService tokenService;
    @Autowired
    private InternalTokenService internalTokenService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private HotelServiceGateway hotelServiceGateway;
    @Autowired
    private JsonMapper mapper;

    private User user;
    private User anotherUser;
    private String bearerToken;

    @DynamicPropertySource
    private static void registerProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @BeforeEach
    void beforeEach() {
        bookingRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.saveAndFlush(new User("history_guest", "test-password-hash", USER));
        anotherUser = userRepository.saveAndFlush(new User("another_guest", "test-password-hash", USER));
        bearerToken = "Bearer " + tokenService.issueToken(user.getId(), USER);
    }

    @AfterEach
    void afterEach() {
        verifyNoInteractions(hotelServiceGateway);
    }

    @Test
    @SneakyThrows
    void shouldReturnOwnOrderedPagesWhenHistoryContainsOtherUsersBookings() {
        final var oldest = createBooking(user, CREATED_AT.minusSeconds(2), CANCELLED, true);
        final var firstTie = createBooking(user, CREATED_AT, PENDING, false);
        createBooking(anotherUser, CREATED_AT.plusSeconds(2), CONFIRMED, false);
        final var newest = createBooking(user, CREATED_AT.plusSeconds(1), CONFIRMED, false);
        final var secondTie = createBooking(user, CREATED_AT, CANCELLED, false);

        final var firstPage = mockMvc.perform(get("/api/v1/bookings?page=0&size=2").header(AUTHORIZATION, bearerToken));
        final var secondPage = mockMvc.perform(get("/api/v1/bookings?page=1&size=2").header(AUTHORIZATION, bearerToken));

        firstPage.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].id").value(newest.getId()))
            .andExpect(jsonPath("$.content[1].id").value(firstTie.getId()))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalElements").value(4));
        secondPage.andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").value(secondTie.getId()))
            .andExpect(jsonPath("$.content[1].id").value(oldest.getId()))
            .andExpect(jsonPath("$.content[1].releasePending").value(true))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.totalElements").value(4));
    }

    @ParameterizedTest
    @CsvSource({"PENDING,false", "CONFIRMED,false", "CANCELLED,false", "CANCELLED,true"})
    @SneakyThrows
    void shouldReturnPublicBookingFieldsWhenOwnerReadsAnyStatus(BookingStatus bookingStatus, boolean releasePending) {
        final var booking = createBooking(user, CREATED_AT, bookingStatus, releasePending);

        final var result = mockMvc.perform(get("/api/v1/bookings/{id}", booking.getId())
                .header(AUTHORIZATION, bearerToken))
            .andExpect(status().isOk())
            .andReturn();
        final var response = mapper.readTree(result.getResponse().getContentAsString());

        assertThat(response.size()).isEqualTo(8);
        assertThat(response.get("id").longValue()).isEqualTo(booking.getId());
        assertThat(response.get("requestId").asString()).isEqualTo(booking.getRequestId().toString());
        assertThat(response.get("roomId").longValue()).isEqualTo(42);
        assertThat(response.get("startDate").asString()).isEqualTo("2027-01-10");
        assertThat(response.get("endDate").asString()).isEqualTo("2027-01-11");
        assertThat(response.get("status").asString()).isEqualTo(bookingStatus.name());
        assertThat(response.get("createdAt").asString()).isEqualTo(CREATED_AT.toString());
        assertThat(response.get("releasePending").booleanValue()).isEqualTo(releasePending);
    }

    @Test
    @SneakyThrows
    void shouldUseDefaultPaginationWhenParametersAreAbsent() {
        final var booking = createBooking(user, CREATED_AT, PENDING, false);

        final var result = mockMvc.perform(get("/api/v1/bookings").header(AUTHORIZATION, bearerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(booking.getId()))
            .andReturn();

        final var response = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.size()).isEqualTo(4);
        assertThat(response.get("content").get(0).size()).isEqualTo(8);
    }

    @Test
    @SneakyThrows
    void shouldReturnEmptyHistoryWhenOnlyAnotherUserHasBookings() {
        createBooking(anotherUser, CREATED_AT, CONFIRMED, false);

        final var result = mockMvc.perform(get("/api/v1/bookings").header(AUTHORIZATION, bearerToken));

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, Integer.MAX_VALUE})
    @SneakyThrows
    void shouldReturnEmptyPageWhenOffsetExceedsPersonalHistory(int page) {
        createBooking(user, CREATED_AT, CONFIRMED, false);

        final var result = mockMvc.perform(get("/api/v1/bookings").param("page", Integer.toString(page))
            .param("size", "100").header(AUTHORIZATION, bearerToken));

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.page").value(page))
            .andExpect(jsonPath("$.size").value(100))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @SneakyThrows
    void shouldReturnNullRoomWhenPendingBookingHasNoSelectedRoom() {
        final var booking = bookingRepository.saveAndFlush(new Booking(user, randomUUID(), true, 11L, null,
            LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 11), CREATED_AT, CREATED_AT.plusSeconds(10)));

        final var detail = mockMvc.perform(get("/api/v1/bookings/{id}", booking.getId()).header(AUTHORIZATION, bearerToken));
        final var history = mockMvc.perform(get("/api/v1/bookings").header(AUTHORIZATION, bearerToken));

        detail.andExpect(status().isOk())
            .andExpect(jsonPath("$.roomId").isEmpty())
            .andExpect(jsonPath("$.status").value("PENDING"));
        history.andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").value(booking.getId()))
            .andExpect(jsonPath("$.content[0].roomId").isEmpty());
    }

    @Test
    @SneakyThrows
    void shouldHideBookingWhenAuthenticatedUserIsNotOwner() {
        final var booking = createBooking(anotherUser, CREATED_AT, CONFIRMED, false);

        final var foreign = mockMvc.perform(get("/api/v1/bookings/{id}", booking.getId()).header(AUTHORIZATION, bearerToken));
        final var missing = mockMvc.perform(get("/api/v1/bookings/{id}", Long.MAX_VALUE).header(AUTHORIZATION, bearerToken));

        foreign.andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"))
            .andExpect(jsonPath("$.detail").value("Booking not found"))
            .andExpect(jsonPath("$.bookingId").doesNotExist())
            .andExpect(jsonPath("$.userId").doesNotExist());
        missing.andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"))
            .andExpect(jsonPath("$.detail").value("Booking not found"));
    }

    @Test
    @SneakyThrows
    void shouldUseAuthenticatedOwnerWhenQueryContainsAnotherUserId() {
        final var own = createBooking(user, CREATED_AT, PENDING, false);
        createBooking(anotherUser, CREATED_AT, CONFIRMED, false);

        final var result = mockMvc.perform(get("/api/v1/bookings").param("userId", anotherUser.getId().toString())
            .header(AUTHORIZATION, bearerToken));

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(own.getId()));
    }

    @Test
    @SneakyThrows
    void shouldLeaveStateUnchangedWhenHistoryAndDetailAreRead() {
        final var booking = createBooking(user, CREATED_AT, CANCELLED, true);
        final var before = jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", booking.getId());

        mockMvc.perform(get("/api/v1/bookings").header(AUTHORIZATION, bearerToken)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/bookings/{id}", booking.getId()).header(AUTHORIZATION, bearerToken))
            .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", booking.getId())).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({
        "?page=-1,VALIDATION_FAILED",
        "?size=0,VALIDATION_FAILED",
        "?size=101,VALIDATION_FAILED",
        "?page=2147483648,INVALID_REQUEST_PARAMETERS",
        "?size=2147483648,INVALID_REQUEST_PARAMETERS",
        "?page=invalid,INVALID_REQUEST_PARAMETERS",
        "?size=1.5,INVALID_REQUEST_PARAMETERS"
    })
    @SneakyThrows
    void shouldRejectHistoryWhenPaginationIsInvalid(String query, String expectedCode) {
        final var result = mockMvc.perform(get("/api/v1/bookings" + query).header(AUTHORIZATION, bearerToken));

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(expectedCode));
    }

    @ParameterizedTest
    @CsvSource({
        "0,VALIDATION_FAILED",
        "9223372036854775808,INVALID_REQUEST_PARAMETERS"
    })
    @SneakyThrows
    void shouldRejectDetailWhenIdIsZeroOrOverflows(String id, String expectedCode) {
        final var result = mockMvc.perform(get("/api/v1/bookings/" + id).header(AUTHORIZATION, bearerToken));

        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(expectedCode));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "1.5"})
    @SneakyThrows
    void shouldReturnNotFoundWhenIdDoesNotMatchNumericRoute(String id) {
        final var result = mockMvc.perform(get("/api/v1/bookings/" + id).header(AUTHORIZATION, bearerToken));

        result.andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/bookings", "/api/v1/bookings/1"})
    @SneakyThrows
    void shouldRejectReadWhenTokenIsMissing(String path) {
        final var result = mockMvc.perform(get(path));

        result.andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/bookings", "/api/v1/bookings/1"})
    @SneakyThrows
    void shouldRejectReadWhenRoleIsAdmin(String path) {
        final var token = tokenService.issueToken(user.getId(), ADMIN);

        final var result = mockMvc.perform(get(path).header(AUTHORIZATION, "Bearer " + token));

        result.andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/bookings", "/api/v1/bookings/1"})
    @SneakyThrows
    void shouldRejectReadWhenTokenIsInternal(String path) {
        final var token = internalTokenService.issueToken();

        final var result = mockMvc.perform(get(path).header(AUTHORIZATION, "Bearer " + token));

        result.andExpect(status().isUnauthorized());
    }

    private Booking createBooking(User owner, Instant createdAt, BookingStatus bookingStatus, boolean releasePending) {
        final var booking = bookingRepository.saveAndFlush(new Booking(owner, randomUUID(), false, null, 42L,
            LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 11), createdAt, createdAt.plusSeconds(10)));
        final var releaseReason = bookingStatus == CANCELLED
            ? "COMPENSATION"
            : null;
        final var recoveryIsRequired = bookingStatus == PENDING || releasePending;
        final var nextRecoveryAt = recoveryIsRequired
            ? createdAt.plusSeconds(10).atOffset(UTC)
            : null;
        jdbcTemplate.update("""
            UPDATE bookings SET status = ?, release_pending = ?, release_reason = ?, next_recovery_at = ? WHERE id = ?
            """, bookingStatus.name(), releasePending, releaseReason, nextRecoveryAt, booking.getId());
        return booking;
    }
}
