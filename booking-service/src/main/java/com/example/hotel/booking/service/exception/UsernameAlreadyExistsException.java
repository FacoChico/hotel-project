package com.example.hotel.booking.service.exception;

public final class UsernameAlreadyExistsException extends RuntimeException {

    public UsernameAlreadyExistsException(Throwable cause) {
        super("Username is already in use", cause);
    }
}
