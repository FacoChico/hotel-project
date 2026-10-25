package com.example.hotel.hotel.repository;

import com.example.hotel.hotel.domain.RoomReservation;
import com.example.hotel.hotel.domain.RoomReservationState;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface RoomReservationRepository extends JpaRepository<RoomReservation, Long> {

    Optional<RoomReservation> findByBookingIdAndRoomId(Long bookingId, Long roomId);

    boolean existsByRoomId(Long roomId);

    boolean existsByRoomIdAndStateAndStartDateLessThanAndEndDateGreaterThan(Long roomId,
                                                                            RoomReservationState state,
                                                                            LocalDate endDate,
                                                                            LocalDate startDate);

    boolean existsByRoomIdAndStateAndEndDateGreaterThan(Long roomId, RoomReservationState state, LocalDate date);
}
