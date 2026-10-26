package com.example.hotel.booking.controller.dto;

import java.util.List;

public record BookingPageResponse(
    List<BookingResponse> content,
    int page,
    int size,
    long totalElements
) {
}
