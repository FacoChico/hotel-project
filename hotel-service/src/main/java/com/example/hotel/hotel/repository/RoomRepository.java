package com.example.hotel.hotel.repository;

import com.example.hotel.hotel.domain.Room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.Optional;

import jakarta.persistence.QueryHint;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

public interface RoomRepository extends JpaRepository<Room, Long> {

    @Lock(PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(
        name = "jakarta.persistence.lock.timeout",
        value = "2000"
    ))
    @Query("select room from Room room where room.id = :id")
    Optional<Room> findByIdForUpdate(long id);
}
