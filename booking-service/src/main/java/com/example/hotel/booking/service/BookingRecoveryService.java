package com.example.hotel.booking.service;

import com.example.hotel.booking.gateway.HotelServiceGateway;
import com.example.hotel.booking.gateway.exception.HotelServiceException;
import com.example.hotel.booking.properties.BookingRecoveryProperties;
import com.example.hotel.booking.properties.HotelClientProperties;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static java.lang.System.nanoTime;
import static java.time.Duration.ofMillis;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingRecoveryService {
    private final BookingTransactions transactions;
    private final HotelServiceGateway hotelServiceGateway;
    private final BookingRecoveryProperties properties;
    private final HotelClientProperties hotelClientProperties;
    private final Clock clock;

    public void recoverBookings() {
        final var startedAt = nanoTime();
        final var budget = ofMillis(properties.cycleTimeoutMs());
        final var cycleDeadline = clock.instant().plus(budget);
        final List<Long> candidates;
        try {
            candidates = transactions.findRecoveryCandidates(clock.instant(), properties.batchSize());
        } catch (DataAccessException exception) {
            log.warn("Booking recovery could not read candidates");
            return;
        }

        for (final var bookingId : candidates) {
            final var remaining = budget.minusNanos(nanoTime() - startedAt);
            final var budgetIsExhausted = remaining.isNegative() || remaining.isZero();
            final var cycleIsExpired = !clock.instant().isBefore(cycleDeadline);
            if (budgetIsExhausted
                || cycleIsExpired
                || Thread.currentThread().isInterrupted()) {
                return;
            }

            final var attemptBudget = remaining.compareTo(ofMillis(hotelClientProperties.operationTimeoutMs())) < 0
                ? remaining
                : ofMillis(hotelClientProperties.operationTimeoutMs());
            final var attemptDeadline = clock.instant().plus(attemptBudget);
            final var deadline = attemptDeadline.isBefore(cycleDeadline)
                ? attemptDeadline
                : cycleDeadline;
            try {
                recoverBooking(bookingId, deadline);
            } catch (DataAccessException exception) {
                log.warn("Booking recovery could not save state bookingId={}", bookingId);
            }
        }
    }

    private void recoverBooking(long bookingId, Instant deadline) {
        final var retryAt = deadline.plusMillis(properties.retryDelayMs());
        final var claimed = transactions.claimRecovery(bookingId, retryAt);
        if (claimed.isEmpty()) {
            return;
        }

        final var booking = claimed.orElseThrow();
        log.info("Booking recovery started bookingId={} requestId={} roomId={} reason={} nextRecoveryAt={}",
            bookingId, booking.getRequestId(), booking.getRoomId(), booking.getReleaseReason(), booking.getNextRecoveryAt());
        try {
            hotelServiceGateway.release(booking, deadline);
            transactions.completeRelease(bookingId);
            log.info("Booking recovery completed bookingId={} requestId={} roomId={} reason={}",
                bookingId, booking.getRequestId(), booking.getRoomId(), booking.getReleaseReason());
        } catch (HotelServiceException exception) {
            log.warn("Booking recovery release incomplete bookingId={} requestId={} roomId={} reason={} code={}",
                bookingId, booking.getRequestId(), booking.getRoomId(), booking.getReleaseReason(), exception.getCode());
        }
    }
}
