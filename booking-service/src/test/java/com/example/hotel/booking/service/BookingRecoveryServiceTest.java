package com.example.hotel.booking.service;

import com.example.hotel.booking.domain.Booking;
import com.example.hotel.booking.domain.BookingReleaseReason;
import com.example.hotel.booking.domain.BookingStatus;
import com.example.hotel.booking.domain.User;
import com.example.hotel.booking.gateway.HotelServiceGateway;
import com.example.hotel.booking.gateway.exception.HotelServiceException;
import com.example.hotel.booking.properties.BookingRecoveryProperties;
import com.example.hotel.booking.properties.HotelClientProperties;
import com.example.hotel.booking.repository.BookingRepository;
import com.example.hotel.booking.repository.UserRepository;
import com.example.hotel.booking.testutil.JwtTestKeys;
import com.example.hotel.booking.testutil.MutableClock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.SneakyThrows;

import static com.example.hotel.booking.domain.BookingReleaseReason.COMPENSATION;
import static com.example.hotel.booking.domain.BookingStatus.CANCELLED;
import static com.example.hotel.booking.domain.BookingStatus.CONFIRMED;
import static com.example.hotel.booking.domain.BookingStatus.PENDING;
import static com.example.hotel.booking.domain.UserRole.USER;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive;

@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "security.bootstrap-admin.enabled=false",
    "booking.recovery.enabled=false",
    "booking.recovery.batch-size=2",
    "booking.recovery.cycle-timeout-ms=2000",
    "booking.recovery.retry-delay-ms=1000",
    "booking.hotel-client.operation-timeout-ms=500",
    "spring.datasource.url=jdbc:h2:mem:booking_recovery_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@Import(BookingRecoveryServiceTest.ClockConfig.class)
class BookingRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2027-01-10T12:00:00Z");
    @Autowired
    private BookingRecoveryService recoveryService;
    @MockitoSpyBean
    private BookingTransactions transactions;
    @MockitoBean
    private HotelServiceGateway hotelServiceGateway;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MutableClock clock;
    @Autowired
    private BookingRecoveryProperties properties;
    @Autowired
    private HotelClientProperties clientProperties;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User user;

    @DynamicPropertySource
    private static void registerProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @BeforeEach
    void beforeEach() {
        clock.set(NOW);
        bookingRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.saveAndFlush(new User("hotel_guest", "test-password-hash", USER));
    }

    @Test
    void shouldPersistCancellationBeforeHttpWhenPendingBookingHasExpired() {
        final var booking = createBooking(NOW);
        doAnswer(invocation -> {
            final var persisted = reload(booking);
            assertThat(isActualTransactionActive()).isFalse();
            assertThat(persisted.getStatus()).isEqualTo(CANCELLED);
            assertThat(persisted.isReleasePending()).isTrue();
            assertThat(persisted.getNextRecoveryAt()).isEqualTo(NOW.plusMillis(1500));
            return null;
        }).when(hotelServiceGateway).release(any(), any());

        recoveryService.recoverBookings();

        final var persisted = reload(booking);
        assertThat(persisted.getStatus()).isEqualTo(CANCELLED);
        assertThat(persisted.getReleaseReason()).isEqualTo(COMPENSATION);
        assertThat(persisted.isReleasePending()).isFalse();
        assertThat(persisted.getNextRecoveryAt()).isNull();
        verify(hotelServiceGateway).release(any(), eq(NOW.plusMillis(500)));
    }

    @ParameterizedTest
    @EnumSource(BookingReleaseReason.class)
    void shouldPreserveReleaseReasonWhenCancelledBookingIsRecovered(BookingReleaseReason reason) {
        final var booking = createBooking(NOW);
        jdbcTemplate.update("""
            UPDATE bookings SET status = 'CANCELLED', release_pending = TRUE, release_reason = ? WHERE id = ?
            """, reason.name(), booking.getId());
        doAnswer(invocation -> {
            final Booking released = invocation.getArgument(0);
            assertThat(released.getReleaseReason()).isEqualTo(reason);
            return null;
        }).when(hotelServiceGateway).release(any(), any());

        recoveryService.recoverBookings();

        assertThat(reload(booking).getReleaseReason()).isEqualTo(reason);
        assertThat(reload(booking).isReleasePending()).isFalse();
    }

    @Test
    void shouldCancelWithoutHttpWhenNoRoomHasBeenSelected() {
        final var booking = bookingRepository.saveAndFlush(new Booking(user, randomUUID(), true, 11L, null,
            LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 11), NOW.minusSeconds(10), NOW));

        recoveryService.recoverBookings();

        assertThat(reload(booking).getStatus()).isEqualTo(CANCELLED);
        assertThat(reload(booking).isReleasePending()).isFalse();
        assertThat(reload(booking).getNextRecoveryAt()).isNull();
        verifyNoInteractions(hotelServiceGateway);
    }

    @Test
    void shouldSkipBookingWhenPendingDeadlineHasNotExpired() {
        final var booking = createBooking(NOW.plusSeconds(1));

        recoveryService.recoverBookings();

        assertThat(reload(booking).getStatus()).isEqualTo(PENDING);
        verifyNoInteractions(hotelServiceGateway);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"CONFIRMED", "CANCELLED"})
    void shouldRecheckBookingWhenCandidateListIsStale(BookingStatus status) {
        final var booking = createBooking(NOW.plusSeconds(1));
        if (status == CONFIRMED) {
            transactions.confirm(booking.getId());
        } else {
            transactions.cancelPending(booking.getId());
            transactions.completeRelease(booking.getId());
        }
        doReturn(List.of(booking.getId())).when(transactions).findRecoveryCandidates(any(), anyInt());

        recoveryService.recoverBookings();

        assertThat(reload(booking).getStatus()).isEqualTo(status);
        assertThat(reload(booking).getNextRecoveryAt()).isNull();
        verifyNoInteractions(hotelServiceGateway);
    }

    @Test
    void shouldRetryLaterWhenHotelReleaseFails() {
        final var booking = createBooking(NOW);
        doThrow(hotelFailure()).when(hotelServiceGateway).release(any(), any());

        recoveryService.recoverBookings();
        recoveryService.recoverBookings();

        assertThat(reload(booking).getStatus()).isEqualTo(CANCELLED);
        assertThat(reload(booking).isReleasePending()).isTrue();
        assertThat(reload(booking).getNextRecoveryAt()).isEqualTo(NOW.plusMillis(1500));
        verify(hotelServiceGateway).release(any(), any());
    }

    @Test
    void shouldCompleteTaskWhenDependencyRecoversAfterRetryTime() {
        final var booking = createBooking(NOW);
        doThrow(hotelFailure()).when(hotelServiceGateway).release(any(), any());
        recoveryService.recoverBookings();
        clock.advance(ofMillis(1500));
        doNothing().when(hotelServiceGateway).release(any(), any());

        recoveryService.recoverBookings();

        assertThat(reload(booking).isReleasePending()).isFalse();
        assertThat(reload(booking).getNextRecoveryAt()).isNull();
        verify(hotelServiceGateway, times(2)).release(any(), any());
    }

    @Test
    void shouldReachOtherTasksWhenOldestTasksKeepFailing() {
        final var first = createBooking(NOW.minusSeconds(3));
        final var second = createBooking(NOW.minusSeconds(2));
        final var third = createBooking(NOW.minusSeconds(1));
        doThrow(hotelFailure()).when(hotelServiceGateway).release(any(), any());

        recoveryService.recoverBookings();
        recoveryService.recoverBookings();

        assertThat(reload(first).getStatus()).isEqualTo(CANCELLED);
        assertThat(reload(second).getStatus()).isEqualTo(CANCELLED);
        assertThat(reload(third).getStatus()).isEqualTo(CANCELLED);
        verify(hotelServiceGateway, times(3)).release(any(), any());
    }

    @Test
    void shouldLeaveExtraCandidatesWhenBatchLimitIsReached() {
        createBooking(NOW.minusSeconds(3));
        createBooking(NOW.minusSeconds(2));
        final var third = createBooking(NOW.minusSeconds(1));

        recoveryService.recoverBookings();

        assertThat(reload(third).getStatus()).isEqualTo(PENDING);
        verify(hotelServiceGateway, times(2)).release(any(), any());
    }

    @Test
    void shouldStopCycleWhenTimeBudgetHasExpired() {
        createBooking(NOW.minusSeconds(2));
        final var second = createBooking(NOW.minusSeconds(1));
        doAnswer(invocation -> {
            clock.advance(ofSeconds(3));
            return null;
        }).when(hotelServiceGateway).release(any(), any());

        recoveryService.recoverBookings();

        assertThat(reload(second).getStatus()).isEqualTo(PENDING);
        verify(hotelServiceGateway).release(any(), any());
    }

    @Test
    void shouldRetainTaskWhenSavingReleaseCompletionFails() {
        final var booking = createBooking(NOW);
        final var completions = new AtomicInteger();
        doAnswer(invocation -> {
            if (completions.incrementAndGet() == 1) {
                throw new TransientDataAccessResourceException("Test persistence failure");
            }
            return invocation.callRealMethod();
        }).when(transactions).completeRelease(anyLong());

        recoveryService.recoverBookings();

        assertThat(reload(booking).isReleasePending()).isTrue();
        clock.advance(ofMillis(1500));

        recoveryService.recoverBookings();

        assertThat(reload(booking).isReleasePending()).isFalse();
        verify(hotelServiceGateway, times(2)).release(any(), any());
    }

    @Test
    void shouldContinueBatchWhenClaimingOneTaskFails() {
        final var first = createBooking(NOW.minusSeconds(2));
        final var second = createBooking(NOW.minusSeconds(1));
        doThrow(new TransientDataAccessResourceException("Test persistence failure"))
            .when(transactions).claimRecovery(eq(first.getId()), any());

        recoveryService.recoverBookings();

        assertThat(reload(first).getStatus()).isEqualTo(PENDING);
        assertThat(reload(second).isReleasePending()).isFalse();
        assertThat(reload(second).getStatus()).isEqualTo(CANCELLED);
        verify(hotelServiceGateway).release(any(), any());
    }

    @Test
    void shouldLeaveTaskUntouchedWhenCandidateLookupFails() {
        final var booking = createBooking(NOW);
        doThrow(new TransientDataAccessResourceException("Test persistence failure"))
            .when(transactions).findRecoveryCandidates(any(), anyInt());

        recoveryService.recoverBookings();

        assertThat(reload(booking).getStatus()).isEqualTo(PENDING);
        assertThat(reload(booking).getNextRecoveryAt()).isEqualTo(NOW);
        verifyNoInteractions(hotelServiceGateway);
    }

    @Test
    @SneakyThrows
    void shouldClaimOnlyOnceWhenRecoveryWorkersRunConcurrently() {
        // given
        final var booking = createBooking(NOW);
        final var enteredRelease = new CountDownLatch(1);
        final var allowRelease = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredRelease.countDown();
            if (!allowRelease.await(3, SECONDS)) {
                throw new IllegalStateException("Test release gate was not opened");
            }
            return null;
        }).when(hotelServiceGateway).release(any(), any());
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // when
            final var first = executor.submit(recoveryService::recoverBookings);
            assertThat(enteredRelease.await(2, SECONDS)).isTrue();
            executor.submit(recoveryService::recoverBookings).get(2, SECONDS);
            allowRelease.countDown();
            first.get(2, SECONDS);

            // then
            assertThat(reload(booking).isReleasePending()).isFalse();
            verify(hotelServiceGateway).release(any(), any());
        } finally {
            allowRelease.countDown();
        }
    }

    @Test
    void shouldKeepCancelledWhenConfirmationArrivesDuringRecovery() {
        final var booking = createBooking(NOW);
        doAnswer(invocation -> {
            final var confirmed = transactions.confirm(booking.getId());
            assertThat(confirmed.getStatus()).isEqualTo(CANCELLED);
            return null;
        }).when(hotelServiceGateway).release(any(), any());

        recoveryService.recoverBookings();

        assertThat(reload(booking).getStatus()).isEqualTo(CANCELLED);
        assertThat(reload(booking).isReleasePending()).isFalse();
    }

    @Test
    @SneakyThrows
    void shouldAcceptOneClaimWhenWorkersUseSameCandidate() {
        // given
        final var booking = createBooking(NOW);
        final var start = new CountDownLatch(1);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var first = executor.submit(() -> {
                start.await();
                return transactions.claimRecovery(booking.getId(), NOW.plusSeconds(3));
            });
            final var second = executor.submit(() -> {
                start.await();
                return transactions.claimRecovery(booking.getId(), NOW.plusSeconds(3));
            });

            // when
            start.countDown();
            final var claims = List.of(first.get(2, SECONDS).isPresent(), second.get(2, SECONDS).isPresent());

            // then
            assertThat(claims).containsExactlyInAnyOrder(true, false);
            assertThat(reload(booking).getStatus()).isEqualTo(CANCELLED);
        } finally {
            start.countDown();
        }
    }

    @Test
    @SneakyThrows
    void shouldKeepTaskCompletedWhenAnOlderWorkerFailsAfterReclaim() {
        // given
        final var booking = createBooking(NOW);
        final var enteredFirstRelease = new CountDownLatch(1);
        final var allowFirstFailure = new CountDownLatch(1);
        final var calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                enteredFirstRelease.countDown();
                if (!allowFirstFailure.await(3, SECONDS)) {
                    throw new IllegalStateException("Test failure gate was not opened");
                }
                throw hotelFailure();
            }
            return null;
        }).when(hotelServiceGateway).release(any(), any());
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // when
            final var first = executor.submit(recoveryService::recoverBookings);
            assertThat(enteredFirstRelease.await(2, SECONDS)).isTrue();
            clock.advance(ofSeconds(2));
            recoveryService.recoverBookings();
            allowFirstFailure.countDown();
            first.get(2, SECONDS);

            // then
            assertThat(reload(booking).isReleasePending()).isFalse();
            assertThat(reload(booking).getNextRecoveryAt()).isNull();
            verify(hotelServiceGateway, times(2)).release(any(), any());
        } finally {
            allowFirstFailure.countDown();
        }
    }

    @Test
    void shouldReclaimTaskWhenPreviousWorkerStoppedBeforeRelease() {
        final var booking = createBooking(NOW);
        transactions.claimRecovery(booking.getId(), NOW.plusSeconds(3));
        final var replacementWorker = new BookingRecoveryService(transactions, hotelServiceGateway,
            properties, clientProperties, clock);

        replacementWorker.recoverBookings();
        verifyNoInteractions(hotelServiceGateway);
        clock.advance(ofSeconds(3));

        replacementWorker.recoverBookings();

        assertThat(reload(booking).isReleasePending()).isFalse();
        verify(hotelServiceGateway).release(any(), any());
    }

    @Test
    void shouldSkipClaimsWhenRetryWindowHasAlreadyExpired() {
        final var booking = createBooking(NOW);

        final var claimed = transactions.claimRecovery(booking.getId(), NOW);

        assertThat(claimed).isEmpty();
        assertThat(reload(booking).getStatus()).isEqualTo(PENDING);
    }

    private Booking createBooking(Instant deadline) {
        return bookingRepository.saveAndFlush(new Booking(user, randomUUID(), false, null, 42L,
            LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 11), NOW.minusSeconds(30), deadline));
    }

    private Booking reload(Booking booking) {
        return bookingRepository.findById(booking.getId()).orElseThrow();
    }

    private static HotelServiceException hotelFailure() {
        return new HotelServiceException(SERVICE_UNAVAILABLE, "HOTEL_SERVICE_UNAVAILABLE", "Test dependency failure", true);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        private static MutableClock recoveryClock() {
            return new MutableClock(NOW);
        }
    }
}
