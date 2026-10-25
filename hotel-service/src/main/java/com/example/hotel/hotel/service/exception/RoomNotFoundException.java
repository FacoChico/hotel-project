package com.example.hotel.hotel.service.exception;

public final class RoomNotFoundException extends RuntimeException {

    public RoomNotFoundException() {
        super("Room was not found");
    }
}
