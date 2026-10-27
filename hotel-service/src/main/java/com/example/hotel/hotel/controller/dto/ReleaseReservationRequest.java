package com.example.hotel.hotel.controller.dto;

import com.example.hotel.hotel.domain.ReservationReleaseReason;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import static io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE;

@Schema(additionalProperties = FALSE)
public record ReleaseReservationRequest(
    @NotNull @Positive Long bookingId,
    @NotNull UUID requestId,
    @NotNull ReservationReleaseReason reason
) {
}
