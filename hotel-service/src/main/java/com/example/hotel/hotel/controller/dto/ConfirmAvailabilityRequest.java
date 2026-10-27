package com.example.hotel.hotel.controller.dto;

import com.example.hotel.hotel.controller.serialization.IsoDateConverter;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

import static io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE;

@Schema(additionalProperties = FALSE)
public record ConfirmAvailabilityRequest(
    @NotNull @Positive Long bookingId,
    @NotNull UUID requestId,
    @NotNull @JsonDeserialize(converter = IsoDateConverter.class) LocalDate startDate,
    @NotNull @JsonDeserialize(converter = IsoDateConverter.class) LocalDate endDate
) {
}
