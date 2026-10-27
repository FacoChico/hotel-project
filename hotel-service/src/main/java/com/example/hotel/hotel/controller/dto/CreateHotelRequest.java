package com.example.hotel.hotel.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import static io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE;

@Schema(additionalProperties = FALSE)
public record CreateHotelRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Size(max = 500) String address
) {
}
