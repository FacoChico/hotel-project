package com.example.hotel.booking.controller.dto;

import com.example.hotel.booking.domain.BookingStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record BookingResponse(
    long id,
    UUID requestId,
    @Schema(nullable = true, description = "Null if no room has been selected")
    Long roomId,
    LocalDate startDate,
    LocalDate endDate,
    BookingStatus status,
    Instant createdAt,
    boolean releasePending
) {
}
