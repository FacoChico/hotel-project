package com.example.hotel.booking.controller.dto;

import com.example.hotel.booking.controller.serialization.IsoDateConverter;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

import static io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE;

@Schema(additionalProperties = FALSE)
public record CreateBookingRequest(
    @Schema(description = "Client UUID reused for idempotent retries by the same user")
    @NotNull UUID requestId,
    @NotNull Boolean autoSelect,
    @Schema(description = "Required for automatic selection. Must be omitted for explicit selection.")
    @Positive Long hotelId,
    @Schema(description = "Required and positive for explicit selection. Ignored for automatic selection.")
    Long roomId,
    @NotNull @JsonDeserialize(converter = IsoDateConverter.class) LocalDate startDate,
    @NotNull @JsonDeserialize(converter = IsoDateConverter.class) LocalDate endDate
) {
}
