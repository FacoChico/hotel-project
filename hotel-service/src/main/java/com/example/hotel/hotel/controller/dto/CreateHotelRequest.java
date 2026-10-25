package com.example.hotel.hotel.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateHotelRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Size(max = 500) String address
) {
}
