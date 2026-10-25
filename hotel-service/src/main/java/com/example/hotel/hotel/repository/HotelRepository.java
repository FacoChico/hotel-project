package com.example.hotel.hotel.repository;

import com.example.hotel.hotel.domain.Hotel;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HotelRepository extends JpaRepository<Hotel, Long> {
}
