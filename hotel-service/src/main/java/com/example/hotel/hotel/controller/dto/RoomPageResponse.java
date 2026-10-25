package com.example.hotel.hotel.controller.dto;

import java.util.List;

public record RoomPageResponse(
    List<RoomResponse> content,
    int page,
    int size,
    long totalElements
) {
}
