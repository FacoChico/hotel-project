package com.example.hotel.booking.controller.dto;

public record RegisterResponse(
    UserResponse user,
    TokenResponse token
) {
}
