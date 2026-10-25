package com.example.hotel.hotel.service;

import com.example.hotel.hotel.controller.dto.ConfirmAvailabilityRequest;
import com.example.hotel.hotel.controller.dto.ReleaseReservationRequest;
import com.example.hotel.hotel.controller.dto.ReservationResponse;
import com.example.hotel.hotel.domain.RoomReservation;
import com.example.hotel.hotel.repository.RoomRepository;
import com.example.hotel.hotel.repository.RoomReservationRepository;
import com.example.hotel.hotel.service.exception.IdempotencyConflictException;
import com.example.hotel.hotel.service.exception.InvalidReservationDatesException;
import com.example.hotel.hotel.service.exception.ReservationReleasedException;
import com.example.hotel.hotel.service.exception.RoomNotFoundException;
import com.example.hotel.hotel.service.exception.RoomUnavailableException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.example.hotel.hotel.domain.ReservationReleaseReason.COMPENSATION;
import static com.example.hotel.hotel.domain.RoomReservationState.RELEASED;
import static com.example.hotel.hotel.domain.RoomReservationState.RESERVED;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomReservationService {
    private final RoomRepository roomRepository;
    private final RoomReservationRepository reservationRepository;

    @Transactional
    public ReservationResponse confirmAvailability(long roomId, ConfirmAvailabilityRequest request) {
        if (!request.startDate().isBefore(request.endDate())) {
            throw new InvalidReservationDatesException();
        }

        final var room = roomRepository.findByIdForUpdate(roomId)
            .orElseThrow(RoomNotFoundException::new);
        final var existing = reservationRepository.findByBookingIdAndRoomId(request.bookingId(), roomId);
        if (existing.isPresent()) {
            return confirmExistingReservation(existing.orElseThrow(), request);
        }

        if (!room.isAvailable() || reservationExists(roomId, request)) {
            throw new RoomUnavailableException();
        }

        final var reservation = new RoomReservation(
            room, request.bookingId(), request.requestId(), request.startDate(), request.endDate(), RESERVED);
        room.incrementTimesBooked();
        reservationRepository.saveAndFlush(reservation);

        log.info("Reservation recorded: bookingId={}, requestId={}, roomId={}, state={}",
            request.bookingId(), request.requestId(), roomId, RESERVED);
        return toResponse(reservation);
    }

    @Transactional
    public ReservationResponse releaseReservation(long roomId, ReleaseReservationRequest request) {
        final var room = roomRepository.findByIdForUpdate(roomId)
            .orElseThrow(RoomNotFoundException::new);
        final var existing = reservationRepository.findByBookingIdAndRoomId(request.bookingId(), roomId);
        if (existing.isEmpty()) {
            final var released = new RoomReservation(
                room, request.bookingId(), request.requestId(), null, null, RELEASED);
            reservationRepository.saveAndFlush(released);
            log.info("Release recorded before confirmation: bookingId={}, requestId={}, roomId={}, reason={}",
                request.bookingId(), request.requestId(), roomId, request.reason());
            return toResponse(released);
        }

        final var reservation = existing.orElseThrow();
        validateRequestId(reservation, request.requestId());
        if (reservation.getState() == RELEASED) {
            return toResponse(reservation);
        }

        reservation.release();
        if (request.reason() == COMPENSATION) {
            room.decrementTimesBooked();
        }
        reservationRepository.flush();

        log.info("Reservation released: bookingId={}, requestId={}, roomId={}, reason={}",
            request.bookingId(), request.requestId(), roomId, request.reason());
        return toResponse(reservation);
    }

    private static ReservationResponse confirmExistingReservation(RoomReservation reservation,
                                                                  ConfirmAvailabilityRequest request) {
        validateRequestId(reservation, request.requestId());
        if (reservation.getState() == RELEASED) {
            throw new ReservationReleasedException();
        }
        if (!reservation.getStartDate().equals(request.startDate())
            || !reservation.getEndDate().equals(request.endDate())) {
            throw new IdempotencyConflictException();
        }
        return toResponse(reservation);
    }

    private boolean reservationExists(long roomId, ConfirmAvailabilityRequest request) {
        return reservationRepository.existsByRoomIdAndStateAndStartDateLessThanAndEndDateGreaterThan(
            roomId, RESERVED, request.endDate(), request.startDate());
    }

    private static void validateRequestId(RoomReservation reservation, UUID requestId) {
        if (!reservation.getRequestId().equals(requestId)) {
            throw new IdempotencyConflictException();
        }
    }

    private static ReservationResponse toResponse(RoomReservation reservation) {
        return new ReservationResponse(reservation.getBookingId(), reservation.getRoom().getId(), reservation.getState());
    }
}
