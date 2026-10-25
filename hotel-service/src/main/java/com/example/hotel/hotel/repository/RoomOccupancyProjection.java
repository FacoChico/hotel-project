package com.example.hotel.hotel.repository;

public interface RoomOccupancyProjection {

    long getRoomId();

    long getTimesBooked();

    long getBookedNights();
}
