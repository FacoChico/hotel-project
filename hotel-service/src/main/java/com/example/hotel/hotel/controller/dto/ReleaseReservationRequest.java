package com.example.hotel.hotel.controller.dto;

import com.example.hotel.hotel.domain.ReservationReleaseReason;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReleaseReservationRequest(
    @NotNull @Positive Long bookingId,
    @NotNull UUID requestId,
    @NotNull ReservationReleaseReason reason
) {
}
