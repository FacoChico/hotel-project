package com.example.hotel.hotel.service.exception;

public final class HotelNotFoundException extends RuntimeException {

    public HotelNotFoundException() {
        super("Hotel was not found");
    }

    public HotelNotFoundException(Throwable cause) {
        super("Hotel was not found", cause);
    }
}
