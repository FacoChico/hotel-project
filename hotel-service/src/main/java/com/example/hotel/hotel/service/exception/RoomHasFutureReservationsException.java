package com.example.hotel.hotel.service.exception;

public final class RoomHasFutureReservationsException extends RuntimeException {

    public RoomHasFutureReservationsException() {
        super("Room has active current or future reservations");
    }
}
