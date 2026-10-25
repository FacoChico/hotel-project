package com.example.hotel.booking.service.exception;

public final class UserNotFoundException extends RuntimeException {

    public UserNotFoundException() {
        super("User was not found");
    }
}
