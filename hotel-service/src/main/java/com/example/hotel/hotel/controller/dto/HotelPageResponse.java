package com.example.hotel.hotel.controller.dto;

import java.util.List;

public record HotelPageResponse(
    List<HotelResponse> content,
    int page,
    int size,
    long totalElements
) {
}
