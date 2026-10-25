package com.example.hotel.hotel.controller.dto;

public record RoomStatisticsResponse(
    long roomId,
    long timesBooked,
    long bookedNights,
    long totalNights,
    double occupancyRate
) {
}
