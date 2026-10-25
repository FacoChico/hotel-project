package com.example.hotel.booking.gateway.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Getter
public final class HotelServiceException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final boolean retryable;

    public HotelServiceException(HttpStatus status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public static HotelServiceException timeout() {
        return new HotelServiceException(GATEWAY_TIMEOUT, "BOOKING_TIMEOUT", "Booking waiting budget exhausted", false);
    }

    public static HotelServiceException unavailable(boolean retryable) {
        return new HotelServiceException(SERVICE_UNAVAILABLE, "HOTEL_SERVICE_UNAVAILABLE",
            "Hotel Service is temporarily unavailable", retryable);
    }
}
