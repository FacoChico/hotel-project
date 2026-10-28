package com.example.hotel.booking.service;

import com.example.hotel.booking.controller.dto.BookingPageResponse;
import com.example.hotel.booking.controller.dto.BookingResponse;
import com.example.hotel.booking.controller.dto.CreateBookingRequest;
import com.example.hotel.booking.controller.dto.RoomPageResponse;
import com.example.hotel.booking.domain.Booking;
import com.example.hotel.booking.gateway.HotelServiceGateway;
import com.example.hotel.booking.gateway.exception.HotelServiceException;
import com.example.hotel.booking.repository.BookingRepository;
import com.example.hotel.booking.service.exception.BookingCancellationException;
import com.example.hotel.booking.service.exception.BookingCreationException;
import com.example.hotel.booking.service.exception.BookingNotFoundException;
import com.example.hotel.booking.service.exception.InvalidBookingRequestException;
import com.example.hotel.booking.service.exception.UserNotFoundException;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.example.hotel.booking.domain.BookingStatus.CANCELLED;
import static com.example.hotel.booking.domain.BookingStatus.CONFIRMED;
import static java.util.Locale.ROOT;
import static org.springframework.data.domain.Sort.Order.asc;
import static org.springframework.data.domain.Sort.Order.desc;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {
    private final BookingTransactions transactions;
    private final HotelServiceGateway hotelServiceGateway;
    private final BookingRepository bookingRepository;

    public BookingCreationResult createBooking(long userId, CreateBookingRequest request, String userToken) {
        final var acceptance = accept(userId, request);
        var booking = acceptance.booking();
        if (!acceptance.created()) {
            return new BookingCreationResult(toResponse(booking), false);
        }

        try {
            if (booking.isAutoSelect()) {
                booking = selectAndReserveRoom(booking, userToken);
            } else {
                hotelServiceGateway.confirm(booking, booking.getDeadlineAt());
            }
            booking = transactions.confirm(booking.getId());
        } catch (BookingCreationException exception) {
            booking = cancelAndRelease(booking);
            throw new BookingCreationException(exception.getStatus(), exception.getCode(), exception.getMessage(),
                booking.getId(), booking.isReleasePending(), exception);
        } catch (HotelServiceException exception) {
            throw compensate(booking, exception);
        } catch (DataAccessException exception) {
            booking = cancelAndRelease(booking);
            throw storageFailure(booking, exception);
        }

        if (booking.getStatus() != CONFIRMED) {
            final var isCancelled = booking.getStatus() == CANCELLED;
            booking = cancelAndRelease(booking);
            final var status = isCancelled
                ? CONFLICT
                : GATEWAY_TIMEOUT;
            final var code = isCancelled
                ? "BOOKING_CANCELLED"
                : "BOOKING_TIMEOUT";
            final var message = isCancelled
                ? "Booking was cancelled during confirmation"
                : "Booking waiting budget exhausted";
            throw new BookingCreationException(status, code, message,
                booking.getId(), booking.isReleasePending(), null);
        }
        return new BookingCreationResult(toResponse(booking), true);
    }

    private Booking selectAndReserveRoom(Booking booking, String userToken) {
        final var attemptedRoomIds = new LinkedHashSet<Long>();
        while (true) {
            final var candidates = hotelServiceGateway.recommend(booking, userToken, booking.getDeadlineAt(), attemptedRoomIds);
            if (candidates.isEmpty()) {
                throw new HotelServiceException(CONFLICT, "ROOM_UNAVAILABLE", "No recommended room is available", false);
            }
            for (final var roomId : candidates) {
                booking = transactions.selectRoom(booking.getId(), roomId);
                try {
                    hotelServiceGateway.confirm(booking, booking.getDeadlineAt());
                    return booking;
                } catch (HotelServiceException exception) {
                    final var roomIsUnavailable = "ROOM_UNAVAILABLE".equals(exception.getCode());
                    if (!roomIsUnavailable) {
                        throw exception;
                    }
                    hotelServiceGateway.release(booking, booking.getDeadlineAt());
                    attemptedRoomIds.add(roomId);
                }
            }
        }
    }

    public RoomPageResponse recommendRooms(LocalDate startDate, LocalDate endDate, Long hotelId,
                                           int page, int size, String userToken) {
        if (!startDate.isBefore(endDate)) {
            throw new InvalidBookingRequestException("startDate must precede endDate");
        }
        return hotelServiceGateway.recommend(startDate, endDate, hotelId, page, size, userToken);
    }

    @Transactional(readOnly = true)
    public BookingPageResponse listBookings(long userId, int page, int size) {
        final var totalElements = bookingRepository.countByUserId(userId);
        final var pageable = PageRequest.of(page, size, Sort.by(desc("createdAt"), asc("id")));
        if (pageable.getOffset() >= totalElements) {
            return new BookingPageResponse(List.of(), page, size, totalElements);
        }

        final var bookings = bookingRepository.findAllByUserId(userId, pageable).stream()
            .map(BookingService::toResponse)
            .toList();
        return new BookingPageResponse(bookings, page, size, totalElements);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(long userId, long bookingId) {
        final var booking = bookingRepository.findByIdAndUserId(bookingId, userId)
            .orElseThrow(BookingNotFoundException::new);
        return toResponse(booking);
    }

    public BookingResponse cancelBooking(long userId, long bookingId) {
        final BookingTransactions.Cancellation cancellation;
        try {
            cancellation = transactions.cancelBooking(userId, bookingId);
        } catch (DataAccessException exception) {
            throw new BookingCancellationException(exception);
        }

        var booking = cancellation.booking();
        final var releaseIsRequired = cancellation.transitioned() && booking.isReleasePending();
        if (!releaseIsRequired) {
            return toResponse(booking);
        }

        try {
            hotelServiceGateway.release(booking, booking.getNextRecoveryAt());
            booking = transactions.completeRelease(bookingId);
        } catch (HotelServiceException | DataAccessException exception) {
            final var code = exception instanceof HotelServiceException hotelException
                ? hotelException.getCode()
                : "BOOKING_STORAGE_UNAVAILABLE";
            log.warn("Booking cancellation release incomplete bookingId={} requestId={} roomId={} reason={} code={}",
                bookingId, booking.getRequestId(), booking.getRoomId(), booking.getReleaseReason(), code);
        }
        return toResponse(booking);
    }

    private BookingTransactions.Acceptance accept(long userId, CreateBookingRequest request) {
        try {
            return transactions.accept(userId, request);
        } catch (DataIntegrityViolationException exception) {
            if (isConstraintViolation(exception, "uk_bookings_user_request")) {
                return transactions.readAccepted(userId, request);
            }
            if (isConstraintViolation(exception, "fk_bookings_user")) {
                throw new UserNotFoundException();
            }
            throw exception;
        }
    }

    private static boolean isConstraintViolation(DataIntegrityViolationException exception, String name) {
        return exception.getCause() instanceof ConstraintViolationException constraint
            && constraint.getConstraintName() != null
            && constraint.getConstraintName().toLowerCase(ROOT).contains(name);
    }

    private BookingCreationException compensate(Booking booking, HotelServiceException failure) {
        final var cancelled = cancelAndRelease(booking);
        return new BookingCreationException(failure.getStatus(), failure.getCode(), failure.getMessage(),
            cancelled.getId(), cancelled.isReleasePending(), failure);
    }

    private Booking cancelAndRelease(Booking booking) {
        try {
            booking = transactions.cancelPending(booking.getId());
            if (booking.isReleasePending()) {
                try {
                    hotelServiceGateway.release(booking, booking.getDeadlineAt());
                    booking = transactions.completeRelease(booking.getId());
                } catch (HotelServiceException exception) {
                    log.warn("Booking release incomplete bookingId={} requestId={} roomId={} reason={} code={}",
                        booking.getId(), booking.getRequestId(), booking.getRoomId(),
                        booking.getReleaseReason(), exception.getCode());
                }
            }
            return booking;
        } catch (DataAccessException exception) {
            throw storageFailure(booking, exception);
        }
    }

    private static BookingCreationException storageFailure(Booking booking, DataAccessException cause) {
        return new BookingCreationException(SERVICE_UNAVAILABLE, "BOOKING_STORAGE_UNAVAILABLE",
            "Booking state could not be saved", booking.getId(), booking.isReleasePending(), cause);
    }

    private static BookingResponse toResponse(Booking booking) {
        return new BookingResponse(booking.getId(), booking.getRequestId(), booking.getRoomId(),
            booking.getStartDate(), booking.getEndDate(), booking.getStatus(),
            booking.getCreatedAt(), booking.isReleasePending());
    }
}
