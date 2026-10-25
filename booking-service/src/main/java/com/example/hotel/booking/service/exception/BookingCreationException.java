package com.example.hotel.booking.service.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public final class BookingCreationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final long bookingId;
    private final boolean releasePending;

    public BookingCreationException(HttpStatus status,
                                    String code,
                                    String message,
                                    long bookingId,
                                    boolean releasePending,
                                    Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.bookingId = bookingId;
        this.releasePending = releasePending;
    }
}
