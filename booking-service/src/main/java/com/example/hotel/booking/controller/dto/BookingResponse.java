package com.example.hotel.booking.controller.dto;

import com.example.hotel.booking.domain.BookingStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BookingResponse(
    long id,
    UUID requestId,
    Long roomId,
    LocalDate startDate,
    LocalDate endDate,
    BookingStatus status,
    Instant createdAt,
    boolean releasePending
) {
}
