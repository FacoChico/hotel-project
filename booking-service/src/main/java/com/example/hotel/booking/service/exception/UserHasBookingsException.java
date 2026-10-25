package com.example.hotel.booking.service.exception;

public final class UserHasBookingsException extends RuntimeException {

    public UserHasBookingsException() {
        super("User has booking history");
    }

    public UserHasBookingsException(Throwable cause) {
        super("User has booking history", cause);
    }
}
