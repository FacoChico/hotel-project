package com.example.hotel.booking.controller;

import com.example.hotel.booking.domain.Booking;
import com.example.hotel.booking.domain.BookingReleaseReason;
import com.example.hotel.booking.domain.BookingStatus;
import com.example.hotel.booking.domain.User;
import com.example.hotel.booking.gateway.HotelServiceGateway;
import com.example.hotel.booking.gateway.exception.HotelServiceException;
import com.example.hotel.booking.repository.BookingRepository;
import com.example.hotel.booking.repository.UserRepository;
import com.example.hotel.booking.security.InternalTokenService;
import com.example.hotel.booking.security.JwtTokenService;
import com.example.hotel.booking.service.BookingRecoveryService;
import com.example.hotel.booking.service.BookingTransactions;
import com.example.hotel.booking.testutil.JwtTestKeys;
import com.example.hotel.booking.testutil.MutableClock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.SneakyThrows;
import tools.jackson.databind.json.JsonMapper;

import static com.example.hotel.booking.domain.BookingReleaseReason.COMPENSATION;
import static com.example.hotel.booking.domain.BookingReleaseReason.USER_CANCELLATION;
import static com.example.hotel.booking.domain.BookingStatus.CANCELLED;
import static com.example.hotel.booking.domain.BookingStatus.CONFIRMED;
import static com.example.hotel.booking.domain.BookingStatus.PENDING;
import static com.example.hotel.booking.domain.UserRole.ADMIN;
import static com.example.hotel.booking.domain.UserRole.USER;
import static java.time.Duration.ofMillis;
import static java.time.ZoneOffset.UTC;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.LOCATION;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive;

@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "security.bootstrap-admin.enabled=false",
    "booking.recovery.enabled=false",
    "booking.recovery.retry-delay-ms=1000",
    "booking.recovery.cycle-timeout-ms=2000",
    "booking.hotel-client.operation-timeout-ms=500",
    "spring.datasource.url=jdbc:h2:mem:booking_cancellation_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc(print = NONE)
@Import(BookingCancellationTest.ClockConfig.class)
class BookingCancellationTest {
    private static final Instant NOW = Instant.parse("2027-01-10T12:00:00Z");
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
    private BookingRecoveryService recoveryService;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JsonMapper mapper;
    @MockitoBean
    private HotelServiceGateway hotelServiceGateway;
    @MockitoSpyBean
    private BookingTransactions transactions;

    private User user;
    private User anotherUser;
    private String bearerToken;

    @DynamicPropertySource
    private static void registerProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @BeforeEach
    void beforeEach() {
        clock.set(NOW);
        bookingRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.saveAndFlush(new User("cancellation_guest", "test-password-hash", USER));
        anotherUser = userRepository.saveAndFlush(new User("another_guest", "test-password-hash", USER));
        bearerToken = "Bearer " + tokenService.issueToken(user.getId(), USER);
    }

    @ParameterizedTest
    @CsvSource({
        "PENDING,COMPENSATION",
        "CONFIRMED,USER_CANCELLATION"
    })
    @SneakyThrows
    void shouldReleaseWithPersistedReasonWhenOwnerCancelsBooking(BookingStatus initialStatus,
                                                                 BookingReleaseReason expectedReason) {
        final var booking = createBooking(user, initialStatus);
        doAnswer(invocation -> {
            final var persisted = reload(booking);
            assertThat(isActualTransactionActive()).isFalse();
            assertThat(persisted.getStatus()).isEqualTo(CANCELLED);
            assertThat(persisted.getReleaseReason()).isEqualTo(expectedReason);
            assertThat(persisted.isReleasePending()).isTrue();
            assertThat(persisted.getNextRecoveryAt()).isEqualTo(NOW.plusMillis(500));
            return null;
        }).when(hotelServiceGateway).release(any(), any());

        final var result = cancel(booking.getId());
        final var response = mapper.readTree(result.getResponse().getContentAsString());
        final var detail = mockMvc.perform(get("/api/v1/bookings/{id}", booking.getId())
            .header(AUTHORIZATION, bearerToken)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader(LOCATION)).isEqualTo("/api/v1/bookings/" + booking.getId());
        assertThat(response.size()).isEqualTo(8);
        assertThat(response.get("id").longValue()).isEqualTo(booking.getId());
        assertThat(response.get("requestId").asString()).isEqualTo(booking.getRequestId().toString());
        assertThat(response.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(response.get("releasePending").booleanValue()).isFalse();
        assertThat(response).isEqualTo(mapper.readTree(detail.getResponse().getContentAsString()));
        assertThat(reload(booking).getReleaseReason()).isEqualTo(expectedReason);
        assertThat(reload(booking).getNextRecoveryAt()).isNull();
        assertThat(reload(booking).getDeadlineAt()).isEqualTo(NOW.minusSeconds(10));
        assertThat(bookingRepository.count()).isEqualTo(1);
        verify(hotelServiceGateway).release(any(), eq(NOW.plusMillis(500)));
    }

    @ParameterizedTest
    @CsvSource({
        "COMPENSATION,false,200",
        "COMPENSATION,true,202",
        "USER_CANCELLATION,false,200",
        "USER_CANCELLATION,true,202"
    })
    @SneakyThrows
    void shouldReturnExistingCancellationWhenBookingIsAlreadyCancelled(BookingReleaseReason reason,
                                                                       boolean releasePending, int expectedStatus) {
        final var booking = createBooking(user, CANCELLED);
        jdbcTemplate.update("UPDATE bookings SET release_reason = ?, release_pending = ?, next_recovery_at = ? WHERE id = ?",
            reason.name(), releasePending, releasePending
                ? NOW.plusSeconds(3).atOffset(UTC)
                : null, booking.getId());
        final var before = jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", booking.getId());

        final var result = cancel(booking.getId());

        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", booking.getId())).isEqualTo(before);
        verifyNoInteractions(hotelServiceGateway);
    }

    @Test
    @SneakyThrows
    void shouldCompleteWithoutReleaseWhenPendingBookingHasNoRoom() {
        final var booking = bookingRepository.saveAndFlush(new Booking(user, randomUUID(), true, 11L, null,
            LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 11), NOW.minusSeconds(30), NOW.minusSeconds(10)));

        final var result = cancel(booking.getId());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(reload(booking).getStatus()).isEqualTo(CANCELLED);
        assertThat(reload(booking).getReleaseReason()).isEqualTo(COMPENSATION);
        assertThat(reload(booking).isReleasePending()).isFalse();
        assertThat(reload(booking).getNextRecoveryAt()).isNull();
        verifyNoInteractions(hotelServiceGateway);
    }

    @ParameterizedTest
    @ValueSource(ints = {503, 504})
    @SneakyThrows
    void shouldKeepReleaseTaskWhenHotelIsUnavailable(int dependencyStatus) {
        final var booking = createBooking(user, CONFIRMED);
        doThrow(new HotelServiceException(HttpStatus.valueOf(dependencyStatus), "HOTEL_SERVICE_UNAVAILABLE",
            "private upstream diagnostic", true)).when(hotelServiceGateway).release(any(), any());

        final var first = cancel(booking.getId());
        final var repeated = cancel(booking.getId());

        assertThat(first.getResponse().getStatus()).isEqualTo(202);
        assertThat(repeated.getResponse().getStatus()).isEqualTo(202);
        assertThat(first.getResponse().getContentAsString()).doesNotContain("private upstream diagnostic");
        assertThat(mapper.readTree(first.getResponse().getContentAsString()).get("releasePending").booleanValue()).isTrue();
        assertThat(reload(booking).getStatus()).isEqualTo(CANCELLED);
        assertThat(reload(booking).getReleaseReason()).isEqualTo(USER_CANCELLATION);
        assertThat(reload(booking).getNextRecoveryAt()).isEqualTo(NOW.plusMillis(500));
        verify(hotelServiceGateway).release(any(), any());
    }

    @Test
    @SneakyThrows
    void shouldFinishCancellationWhenBackgroundReleaseSucceeds() {
        final var booking = createBooking(user, CONFIRMED);
        doThrow(HotelServiceException.unavailable(true)).when(hotelServiceGateway).release(any(), any());
        cancel(booking.getId());
        clock.advance(ofMillis(500));
        doNothing().when(hotelServiceGateway).release(any(), any());
        recoveryService.recoverBookings();
        final var repeated = cancel(booking.getId());

        assertThat(repeated.getResponse().getStatus()).isEqualTo(200);
        assertThat(reload(booking).isReleasePending()).isFalse();
        assertThat(reload(booking).getReleaseReason()).isEqualTo(USER_CANCELLATION);
        assertThat(reload(booking).getNextRecoveryAt()).isNull();
        verify(hotelServiceGateway, times(2)).release(any(), any());
    }

    @Test
    @SneakyThrows
    void shouldKeepTaskWhenReleaseAcknowledgementCannotBeSaved() {
        final var booking = createBooking(user, CONFIRMED);
        final var completions = new AtomicInteger();
        doAnswer(invocation -> {
            if (completions.incrementAndGet() == 1) {
                throw new TransientDataAccessResourceException("private database diagnostic");
            }
            return invocation.callRealMethod();
        }).when(transactions).completeRelease(anyLong());

        final var result = cancel(booking.getId());
        clock.advance(ofMillis(500));
        recoveryService.recoverBookings();

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private database diagnostic");
        assertThat(reload(booking).getStatus()).isEqualTo(CANCELLED);
        assertThat(reload(booking).isReleasePending()).isFalse();
        verify(hotelServiceGateway, times(2)).release(any(), any());
    }

    @Test
    @SneakyThrows
    void shouldReportStorageFailureWhenCancellationCannotBeSaved() {
        final var booking = createBooking(user, CONFIRMED);
        doThrow(new TransientDataAccessResourceException("private database diagnostic"))
            .when(transactions).cancelBooking(user.getId(), booking.getId());

        final var result = mockMvc.perform(delete("/api/v1/bookings/{id}", booking.getId()).header(AUTHORIZATION, bearerToken));

        result.andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("BOOKING_STORAGE_UNAVAILABLE"))
            .andExpect(jsonPath("$.releasePending").doesNotExist());
        assertThat(reload(booking).getStatus()).isEqualTo(CONFIRMED);
        verifyNoInteractions(hotelServiceGateway);
    }

    @Test
    @SneakyThrows
    void shouldReleaseOnceWhenCancellationRequestsOverlap() {
        // given
        final var booking = createBooking(user, CONFIRMED);
        final var enteredRelease = new CountDownLatch(1);
        final var allowRelease = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredRelease.countDown();
            awaitGate(allowRelease);
            return null;
        }).when(hotelServiceGateway).release(any(), any());
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // when
            final var first = executor.submit(() -> cancel(booking.getId()));
            assertThat(enteredRelease.await(2, SECONDS)).isTrue();
            final var repeated = cancel(booking.getId());
            allowRelease.countDown();
            final var completed = first.get(2, SECONDS);

            // then
            assertThat(repeated.getResponse().getStatus()).isEqualTo(202);
            assertThat(completed.getResponse().getStatus()).isEqualTo(200);
            assertThat(reload(booking).isReleasePending()).isFalse();
            verify(hotelServiceGateway).release(any(), any());
        } finally {
            allowRelease.countDown();
        }
    }

    @Test
    @SneakyThrows
    void shouldRollBackCancellationWhenDatabaseRejectsStateChange() {
        // given
        final var booking = createBooking(user, CONFIRMED);
        final var before = jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", booking.getId());
        jdbcTemplate.execute("ALTER TABLE bookings ADD CONSTRAINT ck_test_booking_cancellation CHECK (status <> 'CANCELLED')");
        try {
            // when
            final var result = cancel(booking.getId());

            // then
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("ck_test_booking_cancellation");
            assertThat(jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", booking.getId())).isEqualTo(before);
            verifyNoInteractions(hotelServiceGateway);
        } finally {
            jdbcTemplate.execute("ALTER TABLE bookings DROP CONSTRAINT ck_test_booking_cancellation");
        }
    }

    @Test
    @SneakyThrows
    void shouldReturnSafeFailureWhenBookingLockTimesOut() {
        // given
        final var booking = createBooking(user, CONFIRMED);
        final var locked = new CountDownLatch(1);
        final var allowCommit = new CountDownLatch(1);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                bookingRepository.findByIdForUpdate(booking.getId()).orElseThrow();
                locked.countDown();
                awaitGate(allowCommit);
            }));
            assertThat(locked.await(2, SECONDS)).isTrue();

            // when
            final var result = cancel(booking.getId());
            allowCommit.countDown();
            holder.get(2, SECONDS);

            // then
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("code").asString())
                .isEqualTo("BOOKING_STORAGE_UNAVAILABLE");
            assertThat(reload(booking).getStatus()).isEqualTo(CONFIRMED);
            verifyNoInteractions(hotelServiceGateway);
        } finally {
            allowCommit.countDown();
        }
    }

    @Test
    @SneakyThrows
    void shouldHideBookingWhenCancellationRequesterIsNotOwner() {
        final var booking = createBooking(anotherUser, CONFIRMED);

        final var foreign = mockMvc.perform(delete("/api/v1/bookings/{id}", booking.getId())
            .param("userId", anotherUser.getId().toString()).header(AUTHORIZATION, bearerToken));
        final var missing = mockMvc.perform(delete("/api/v1/bookings/{id}", Long.MAX_VALUE)
            .header(AUTHORIZATION, bearerToken));

        foreign.andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"))
            .andExpect(jsonPath("$.detail").value("Booking not found"));
        missing.andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"))
            .andExpect(jsonPath("$.detail").value("Booking not found"));
        assertThat(reload(booking).getStatus()).isEqualTo(CONFIRMED);
        verifyNoInteractions(hotelServiceGateway);
    }

    @ParameterizedTest
    @CsvSource({
        "0,400",
        "9223372036854775808,400",
        "abc,404",
        "-1,404",
        "1.5,404"
    })
    @SneakyThrows
    void shouldRejectCancellationWhenIdIsInvalid(String id, int expectedStatus) {
        final var result = mockMvc.perform(delete("/api/v1/bookings/" + id).header(AUTHORIZATION, bearerToken));

        result.andExpect(status().is(expectedStatus));
        verifyNoInteractions(hotelServiceGateway);
    }

    @Test
    @SneakyThrows
    void shouldRejectCancellationWhenTokenIsMissing() {
        final var booking = createBooking(user, CONFIRMED);

        final var result = mockMvc.perform(delete("/api/v1/bookings/{id}", booking.getId()));

        result.andExpect(status().isUnauthorized());
        assertThat(reload(booking).getStatus()).isEqualTo(CONFIRMED);
        verifyNoInteractions(hotelServiceGateway);
    }

    @Test
    @SneakyThrows
    void shouldRejectCancellationWhenRoleIsAdmin() {
        final var booking = createBooking(user, CONFIRMED);
        final var token = tokenService.issueToken(user.getId(), ADMIN);

        final var result = mockMvc.perform(delete("/api/v1/bookings/{id}", booking.getId())
            .header(AUTHORIZATION, "Bearer " + token));

        result.andExpect(status().isForbidden());
        assertThat(reload(booking).getStatus()).isEqualTo(CONFIRMED);
        verifyNoInteractions(hotelServiceGateway);
    }

    @Test
    @SneakyThrows
    void shouldRejectCancellationWhenTokenIsInternal() {
        final var booking = createBooking(user, CONFIRMED);
        final var token = internalTokenService.issueToken();

        final var result = mockMvc.perform(delete("/api/v1/bookings/{id}", booking.getId())
            .header(AUTHORIZATION, "Bearer " + token));

        result.andExpect(status().isUnauthorized());
        assertThat(reload(booking).getStatus()).isEqualTo(CONFIRMED);
        verifyNoInteractions(hotelServiceGateway);
    }

    @SneakyThrows
    private MvcResult cancel(long bookingId) {
        return mockMvc.perform(delete("/api/v1/bookings/{id}", bookingId).header(AUTHORIZATION, bearerToken)).andReturn();
    }

    private Booking createBooking(User owner, BookingStatus initialStatus) {
        final var booking = bookingRepository.saveAndFlush(new Booking(owner, randomUUID(), false, null, 42L,
            LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 11), NOW.minusSeconds(30), NOW.minusSeconds(10)));
        final var reason = initialStatus == CANCELLED
            ? "COMPENSATION"
            : null;
        final var recoveryAt = initialStatus == PENDING
            ? NOW.minusSeconds(10).atOffset(UTC)
            : null;
        jdbcTemplate.update("UPDATE bookings SET status = ?, release_reason = ?, next_recovery_at = ? WHERE id = ?",
            initialStatus.name(), reason, recoveryAt, booking.getId());
        return booking;
    }

    private Booking reload(Booking booking) {
        return bookingRepository.findById(booking.getId()).orElseThrow();
    }

    @SneakyThrows
    private static void awaitGate(CountDownLatch gate) {
        if (!gate.await(4, SECONDS)) {
            throw new IllegalStateException("Test gate was not opened");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        private static MutableClock cancellationClock() {
            return new MutableClock(NOW);
        }
    }
}
