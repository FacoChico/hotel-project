package com.example.hotel.hotel.service.exception;

public final class HotelHasRoomsException extends RuntimeException {

    public HotelHasRoomsException(Throwable cause) {
        super("Hotel still has rooms", cause);
    }
}
