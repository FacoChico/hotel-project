package com.example.hotel.hotel.service.exception;

public final class RoomNumberAlreadyExistsException extends RuntimeException {

    public RoomNumberAlreadyExistsException(Throwable cause) {
        super("Room number is already in use in this hotel", cause);
    }
}
