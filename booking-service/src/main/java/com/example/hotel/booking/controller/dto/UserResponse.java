package com.example.hotel.booking.controller.dto;

import com.example.hotel.booking.domain.UserRole;

public record UserResponse(
    Long id,
    String username,
    UserRole role
) {
}
