package com.example.hotel.booking.service.exception;

public final class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException() {
        super("Booking not found");
    }
}
