package com.example.hotel.hotel.service.exception;

public final class ReservationReleasedException extends RuntimeException {

    public ReservationReleasedException() {
        super("Reservation has already been released");
    }
}
