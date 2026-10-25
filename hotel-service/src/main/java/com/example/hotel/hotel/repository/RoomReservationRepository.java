package com.example.hotel.hotel.repository;

import com.example.hotel.hotel.domain.RoomReservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoomReservationRepository extends JpaRepository<RoomReservation, Long> {

    Optional<RoomReservation> findByBookingIdAndRoomId(Long bookingId, Long roomId);
}
