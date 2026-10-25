package com.example.hotel.hotel.service.exception;

public final class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Reservation identity conflicts with the accepted request");
    }
}
