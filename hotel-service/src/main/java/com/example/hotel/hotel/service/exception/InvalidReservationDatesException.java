package com.example.hotel.hotel.service.exception;

public final class InvalidReservationDatesException extends RuntimeException {

    public InvalidReservationDatesException() {
        super("Start date must precede end date");
    }
}
