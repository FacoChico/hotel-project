package com.example.hotel.hotel.service.exception;

public final class RoomUnavailableException extends RuntimeException {

    public RoomUnavailableException() {
        super("Room is unavailable for the requested dates");
    }
}
