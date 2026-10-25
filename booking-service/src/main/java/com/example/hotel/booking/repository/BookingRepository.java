package com.example.hotel.booking.repository;

import com.example.hotel.booking.domain.Booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByUserIdAndRequestId(long userId, UUID requestId);

    boolean existsByUserId(long userId);
}
