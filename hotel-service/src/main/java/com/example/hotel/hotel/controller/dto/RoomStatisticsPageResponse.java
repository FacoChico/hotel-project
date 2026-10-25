package com.example.hotel.hotel.controller.dto;

import java.util.List;

public record RoomStatisticsPageResponse(
    List<RoomStatisticsResponse> content,
    int page,
    int size,
    long totalElements
) {
}
