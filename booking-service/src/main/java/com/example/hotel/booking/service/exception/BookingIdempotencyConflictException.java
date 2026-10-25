package com.example.hotel.booking.service.exception;

import lombok.Getter;

@Getter
public final class BookingIdempotencyConflictException extends RuntimeException {
    private final long bookingId;

    public BookingIdempotencyConflictException(long bookingId) {
        super("Request ID is already associated with different booking parameters");
        this.bookingId = bookingId;
    }
}
