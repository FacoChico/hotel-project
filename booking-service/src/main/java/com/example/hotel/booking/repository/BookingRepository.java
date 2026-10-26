package com.example.hotel.booking.repository;

import com.example.hotel.booking.domain.Booking;
import com.example.hotel.booking.domain.BookingStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.QueryHint;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByUserIdAndRequestId(long userId, UUID requestId);

    boolean existsByUserId(long userId);

    @Query("""
        select booking.id
        from Booking booking
        where
            booking.nextRecoveryAt <= :now
            and (booking.status = :pending or (booking.status = :cancelled and booking.releasePending = true))
        order by booking.nextRecoveryAt, booking.id
        """)
    List<Long> findRecoveryCandidates(Instant now, BookingStatus pending, BookingStatus cancelled, Pageable pageable);

    @Lock(PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000"))
    @Query("select booking from Booking booking where booking.id = :id")
    Optional<Booking> findByIdForUpdate(long id);
}
