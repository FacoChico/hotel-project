package com.example.hotel.booking.gateway;

import com.example.hotel.booking.controller.dto.RoomPageResponse;
import com.example.hotel.booking.domain.Booking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface HotelServiceGateway {
    void confirm(Booking booking, Instant deadline);

    void release(Booking booking, Instant deadline);

    List<Long> recommend(Booking booking, String userToken, Instant deadline, Set<Long> excludedRoomIds);

    RoomPageResponse recommend(LocalDate startDate, LocalDate endDate, Long hotelId,
                               int page, int size, String userToken);
}
