package com.example.hotel.hotel.repository;

import com.example.hotel.hotel.domain.Room;
import com.example.hotel.hotel.domain.RoomReservation;

import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;

import lombok.experimental.UtilityClass;

import static com.example.hotel.hotel.domain.RoomReservationState.RESERVED;

@UtilityClass
public class RoomSpecifications {

    public static Specification<Room> excludingRooms(List<Long> roomIds) {
        return (room, query, builder) -> roomIds.isEmpty()
            ? builder.conjunction()
            : builder.not(room.get("id").in(roomIds));
    }

    public static Specification<Room> availableForDates(LocalDate startDate, LocalDate endDate, Long hotelId) {
        return (room, query, builder) -> {
            final var overlappingReservations = query.subquery(Long.class);
            final var reservation = overlappingReservations.from(RoomReservation.class);
            overlappingReservations.select(reservation.get("id"))
                .where(
                    builder.equal(reservation.get("room"), room),
                    builder.equal(reservation.get("state"), RESERVED),
                    builder.lessThan(reservation.get("startDate"), endDate),
                    builder.greaterThan(reservation.get("endDate"), startDate)
                );

            final var available = builder.and(
                builder.isTrue(room.get("available")),
                builder.not(builder.exists(overlappingReservations))
            );
            return hotelId == null
                ? available
                : builder.and(available, builder.equal(room.get("hotel").get("id"), hotelId));
        };
    }
}
