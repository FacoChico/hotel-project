package com.example.hotel.hotel.domain;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static com.example.hotel.hotel.domain.RoomReservationState.RELEASED;
import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.IDENTITY;
import static lombok.AccessLevel.PROTECTED;

@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(name = "room_reservations",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_room_reservations_booking_room", columnNames = {"booking_id", "room_id"}))
public class RoomReservation {
    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false, updatable = false,
        foreignKey = @ForeignKey(name = "fk_room_reservations_room"))
    private Room room;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private Long bookingId;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "start_date", updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", updatable = false)
    private LocalDate endDate;

    @Enumerated(STRING)
    @Column(nullable = false, length = 16)
    private RoomReservationState state;

    public RoomReservation(Room room, Long bookingId, UUID requestId,
                           LocalDate startDate, LocalDate endDate, RoomReservationState state) {
        this.room = room;
        this.bookingId = bookingId;
        this.requestId = requestId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.state = state;
    }

    public void release() {
        state = RELEASED;
    }
}
