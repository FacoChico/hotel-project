package com.example.hotel.hotel.controller.dto;

import com.example.hotel.hotel.domain.RoomReservationState;

public record ReservationResponse(
    long bookingId,
    long roomId,
    RoomReservationState state
) {
}
