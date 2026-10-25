package com.example.hotel.hotel.service.exception;

public final class EmptyHotelUpdateException extends RuntimeException {

    public EmptyHotelUpdateException() {
        super("At least one hotel field is required");
    }
}
