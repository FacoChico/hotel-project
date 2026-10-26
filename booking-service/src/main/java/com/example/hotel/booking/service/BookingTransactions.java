package com.example.hotel.booking.service;

import com.example.hotel.booking.controller.dto.CreateBookingRequest;
import com.example.hotel.booking.domain.Booking;
import com.example.hotel.booking.properties.HotelClientProperties;
import com.example.hotel.booking.repository.BookingRepository;
import com.example.hotel.booking.repository.UserRepository;
import com.example.hotel.booking.service.exception.BookingIdempotencyConflictException;
import com.example.hotel.booking.service.exception.BookingNotFoundException;
import com.example.hotel.booking.service.exception.InvalidBookingRequestException;
import com.example.hotel.booking.service.exception.UserNotFoundException;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.example.hotel.booking.domain.BookingStatus.CANCELLED;
import static com.example.hotel.booking.domain.BookingStatus.PENDING;
import static java.time.ZoneOffset.UTC;
import static java.time.temporal.ChronoUnit.MICROS;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingTransactions {
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final HotelClientProperties properties;
    private final Clock clock;

    @Transactional
    public Acceptance accept(long userId, CreateBookingRequest request) {
        final var accepted = bookingRepository.findByUserIdAndRequestId(userId, request.requestId());
        if (accepted.isPresent()) {
            return repeat(accepted.orElseThrow(), request);
        }

        if (request.autoSelect()) {
            throw new InvalidBookingRequestException("Automatic room selection is not available yet");
        }
        if (request.roomId() == null || request.hotelId() != null) {
            throw new InvalidBookingRequestException("Explicit selection requires roomId without hotelId");
        }

        final var now = clock.instant().truncatedTo(MICROS);
        if (!request.startDate().isBefore(request.endDate())
            || request.startDate().isBefore(LocalDate.ofInstant(now, UTC))) {
            throw new InvalidBookingRequestException("Booking dates must form a future or current-day stay");
        }
        final var user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        final var booking = bookingRepository.saveAndFlush(new Booking(user, request.requestId(), false,
            null, request.roomId(), request.startDate(), request.endDate(), now,
            now.plusMillis(properties.operationTimeoutMs())));
        log.info("Booking accepted bookingId={} requestId={} roomId={} status={}",
            booking.getId(), booking.getRequestId(), booking.getRoomId(), booking.getStatus());
        return new Acceptance(booking, true);
    }

    @Transactional(readOnly = true)
    public Acceptance readAccepted(long userId, CreateBookingRequest request) {
        return repeat(bookingRepository.findByUserIdAndRequestId(userId, request.requestId()).orElseThrow(), request);
    }

    private static Acceptance repeat(Booking booking, CreateBookingRequest request) {
        final var sameSelectionMode = booking.isAutoSelect() == request.autoSelect();
        final var sameSelectionTarget = hasSameSelectionTarget(booking, request);
        final var sameStartDate = booking.getStartDate().equals(request.startDate());
        final var sameEndDate = booking.getEndDate().equals(request.endDate());
        final var sameRequest = sameSelectionMode
            && sameSelectionTarget
            && sameStartDate
            && sameEndDate;

        if (!sameRequest) {
            throw new BookingIdempotencyConflictException(booking.getId());
        }
        return new Acceptance(booking, false);
    }

    private static boolean hasSameSelectionTarget(Booking booking, CreateBookingRequest request) {
        if (booking.isAutoSelect()) {
            return Objects.equals(booking.getHotelId(), request.hotelId());
        }

        final var sameRoom = Objects.equals(booking.getRoomId(), request.roomId());
        final var hotelNotProvided = request.hotelId() == null;
        return sameRoom && hotelNotProvided;
    }

    @Transactional
    public Booking confirm(long bookingId) {
        final var booking = findLocked(bookingId);
        booking.confirm(clock.instant());
        logTransition(booking);
        return booking;
    }

    @Transactional
    public Booking cancelPending(long bookingId) {
        final var booking = findLocked(bookingId);
        booking.cancelPending();
        logTransition(booking);
        return booking;
    }

    @Transactional
    public Cancellation cancelBooking(long userId, long bookingId) {
        final var booking = bookingRepository.findByIdAndUserIdForUpdate(bookingId, userId)
            .orElseThrow(BookingNotFoundException::new);
        final var deadline = clock.instant().truncatedTo(MICROS).plusMillis(properties.operationTimeoutMs());
        final var transitioned = booking.cancelByUser(deadline);
        logTransition(booking);
        return new Cancellation(booking, transitioned);
    }

    @Transactional
    public Booking completeRelease(long bookingId) {
        final var booking = findLocked(bookingId);
        booking.completeRelease();
        logTransition(booking);
        return booking;
    }

    @Transactional(readOnly = true)
    public List<Long> findRecoveryCandidates(Instant now, int batchSize) {
        return bookingRepository.findRecoveryCandidates(now, PENDING, CANCELLED, PageRequest.of(0, batchSize));
    }

    @Transactional
    public Optional<Booking> claimRecovery(long bookingId, Instant retryAt) {
        final var candidate = bookingRepository.findByIdForUpdate(bookingId);
        final var now = clock.instant();
        final var retryWindowIsExpired = !retryAt.isAfter(now);
        if (candidate.isEmpty() || retryWindowIsExpired) {
            return Optional.empty();
        }

        final var booking = candidate.orElseThrow();
        final var claimed = booking.claimRecovery(now, retryAt);
        logTransition(booking);
        return claimed
            ? Optional.of(booking)
            : Optional.empty();
    }

    private Booking findLocked(long id) {
        return bookingRepository.findByIdForUpdate(id).orElseThrow();
    }

    private static void logTransition(Booking booking) {
        log.info("Booking state bookingId={} requestId={} roomId={} status={} releasePending={} reason={}",
            booking.getId(), booking.getRequestId(), booking.getRoomId(), booking.getStatus(),
            booking.isReleasePending(), booking.getReleaseReason());
    }

    public record Acceptance(
        Booking booking,
        boolean created
    ) {
    }

    public record Cancellation(
        Booking booking,
        boolean transitioned
    ) {
    }
}
