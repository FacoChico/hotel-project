package com.example.hotel.booking.service;

import com.example.hotel.booking.controller.dto.BookingResponse;

public record BookingCreationResult(
    BookingResponse booking,
    boolean created
) {
}
