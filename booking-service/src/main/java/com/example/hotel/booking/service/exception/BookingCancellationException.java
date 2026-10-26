package com.example.hotel.booking.service.exception;

public final class BookingCancellationException extends RuntimeException {

    public BookingCancellationException(Throwable cause) {
        super("Booking cancellation could not be saved", cause);
    }
}
