package com.example.hotel.booking.service.exception;

public final class EmptyUserUpdateException extends RuntimeException {

    public EmptyUserUpdateException() {
        super("At least one user field is required");
    }
}
