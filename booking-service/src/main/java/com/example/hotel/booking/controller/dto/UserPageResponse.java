package com.example.hotel.booking.controller.dto;

import java.util.List;

public record UserPageResponse(
    List<UserResponse> content,
    int page,
    int size,
    long totalElements
) {
}
