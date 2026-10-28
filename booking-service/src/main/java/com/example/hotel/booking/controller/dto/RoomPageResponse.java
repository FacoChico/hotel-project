package com.example.hotel.booking.controller.dto;

import java.util.List;

public record RoomPageResponse(List<RoomResponse> content, int page, int size, long totalElements) {
    public record RoomResponse(long id, long hotelId, String number, boolean available, long timesBooked) {
    }
}
