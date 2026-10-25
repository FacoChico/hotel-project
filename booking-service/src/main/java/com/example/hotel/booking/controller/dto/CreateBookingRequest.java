package com.example.hotel.booking.controller.dto;

import com.example.hotel.booking.controller.serialization.IsoDateConverter;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateBookingRequest(
    @NotNull UUID requestId,
    @NotNull Boolean autoSelect,
    @Positive Long hotelId,
    @Positive Long roomId,
    @NotNull @JsonDeserialize(converter = IsoDateConverter.class) LocalDate startDate,
    @NotNull @JsonDeserialize(converter = IsoDateConverter.class) LocalDate endDate
) {
}
