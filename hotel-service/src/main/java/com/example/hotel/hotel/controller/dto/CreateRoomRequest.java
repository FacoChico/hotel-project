package com.example.hotel.hotel.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import static io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE;

@Schema(additionalProperties = FALSE)
public record CreateRoomRequest(
    @NotNull @Positive Long hotelId,
    @NotBlank @Size(max = 32) String number,
    @NotNull Boolean available
) {
}
