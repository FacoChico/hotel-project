package com.example.hotel.hotel.repository;

import com.example.hotel.hotel.domain.Hotel;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HotelRepository extends JpaRepository<Hotel, Long> {

    List<Hotel> findAllBy(Pageable pageable);
}
