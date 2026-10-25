package com.example.hotel.hotel.repository;

import com.example.hotel.hotel.domain.Room;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.QueryHint;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

public interface RoomRepository extends JpaRepository<Room, Long>, JpaSpecificationExecutor<Room> {

    @Lock(PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(
        name = "jakarta.persistence.lock.timeout",
        value = "2000"
    ))
    @Query("select room from Room room where room.id = :id")
    Optional<Room> findByIdForUpdate(long id);

    long countByHotelId(Long hotelId);

    @Query(
        value = """
            SELECT
                    room.id AS "roomId",
                    room.times_booked AS "timesBooked",
                    COALESCE(SUM(DATEDIFF('DAY',
                        GREATEST(reservation.start_date, :startDate),
                        LEAST(reservation.end_date, :endDate))), 0) AS "bookedNights"
            FROM rooms room
                LEFT JOIN room_reservations reservation
                    ON reservation.room_id = room.id
                        AND reservation.state = :state
                        AND reservation.start_date < :endDate
                        AND reservation.end_date > :startDate
            WHERE (:hotelId IS NULL OR room.hotel_id = :hotelId)
            GROUP BY room.id, room.times_booked
            ORDER BY room.id
            """,
        nativeQuery = true
    )
    List<RoomOccupancyProjection> findOccupancyStatistics(LocalDate startDate,
                                                          LocalDate endDate,
                                                          Long hotelId,
                                                          String state,
                                                          Pageable pageable);
}
