package com.example.hotel.hotel.controller.dto;

public record RoomResponse(
    long id,
    long hotelId,
    String number,
    boolean available,
    long timesBooked
) {
}
