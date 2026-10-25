package com.example.hotel.hotel.service.exception;

public final class EmptyRoomUpdateException extends RuntimeException {

    public EmptyRoomUpdateException() {
        super("At least one room field is required");
    }
}
