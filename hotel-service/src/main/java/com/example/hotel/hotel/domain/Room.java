package com.example.hotel.hotel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static jakarta.persistence.FetchType.LAZY;
import static jakarta.persistence.GenerationType.IDENTITY;
import static lombok.AccessLevel.PROTECTED;

@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(name = "rooms", uniqueConstraints = @UniqueConstraint(
    name = "uk_rooms_hotel_number", columnNames = {"hotel_id", "number"}))
public class Room {
    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "hotel_id", nullable = false, updatable = false,
        foreignKey = @ForeignKey(name = "fk_rooms_hotel"))
    private Hotel hotel;

    @Column(nullable = false, length = 32)
    private String number;

    @Column(nullable = false)
    private boolean available;

    @Column(name = "times_booked", nullable = false)
    private long timesBooked;

    public Room(Hotel hotel, String number, boolean available) {
        this.hotel = hotel;
        this.number = number;
        this.available = available;
    }

    public void updateDetails(String number, boolean available) {
        this.number = number;
        this.available = available;
    }
}
