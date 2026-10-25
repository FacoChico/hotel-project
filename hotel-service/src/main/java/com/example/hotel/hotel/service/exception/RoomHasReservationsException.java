package com.example.hotel.hotel.service.exception;

public final class RoomHasReservationsException extends RuntimeException {

    public RoomHasReservationsException() {
        super("Room has reservation history");
    }
}
