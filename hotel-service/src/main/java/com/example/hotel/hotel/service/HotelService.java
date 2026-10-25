package com.example.hotel.hotel.service;

import com.example.hotel.hotel.controller.dto.CreateHotelRequest;
import com.example.hotel.hotel.controller.dto.HotelPageResponse;
import com.example.hotel.hotel.controller.dto.HotelResponse;
import com.example.hotel.hotel.controller.dto.UpdateHotelRequest;
import com.example.hotel.hotel.domain.Hotel;
import com.example.hotel.hotel.repository.HotelRepository;
import com.example.hotel.hotel.service.exception.EmptyHotelUpdateException;
import com.example.hotel.hotel.service.exception.HotelHasRoomsException;
import com.example.hotel.hotel.service.exception.HotelNotFoundException;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import lombok.RequiredArgsConstructor;

import static java.util.Locale.ROOT;

@Service
@RequiredArgsConstructor
public class HotelService {
    private final HotelRepository hotelRepository;

    @Transactional
    public HotelResponse createHotel(CreateHotelRequest request) {
        final var hotel = hotelRepository.save(new Hotel(request.name(), request.address()));
        return toResponse(hotel);
    }

    @Transactional(readOnly = true)
    public HotelPageResponse listHotels(int page, int size) {
        final var totalElements = hotelRepository.count();
        final var pageable = PageRequest.of(page, size, Sort.by("id"));
        if (pageable.getOffset() >= totalElements) {
            return new HotelPageResponse(List.of(), page, size, totalElements);
        }

        final var hotels = hotelRepository.findAllBy(pageable).stream()
            .map(HotelService::toResponse)
            .toList();
        return new HotelPageResponse(hotels, page, size, totalElements);
    }

    @Transactional(readOnly = true)
    public HotelResponse getHotel(long id) {
        return toResponse(findHotel(id));
    }

    @Transactional
    public HotelResponse updateHotel(long id, UpdateHotelRequest request) {
        if (request.getName() == null && request.getAddress() == null) {
            throw new EmptyHotelUpdateException();
        }

        final var hotel = findHotel(id);
        final var name = request.getName() == null
            ? hotel.getName()
            : request.getName();
        final var address = request.getAddress() == null
            ? hotel.getAddress()
            : request.getAddress();
        hotel.updateDetails(name, address);
        return toResponse(hotel);
    }

    @Transactional
    public void deleteHotel(long id) {
        final var hotel = findHotel(id);
        try {
            hotelRepository.delete(hotel);
            hotelRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            if (exception.getCause() instanceof ConstraintViolationException constraint
                && constraint.getConstraintName() != null
                && constraint.getConstraintName().toLowerCase(ROOT).contains("fk_rooms_hotel")) {
                throw new HotelHasRoomsException(exception);
            }
            throw exception;
        }
    }

    private Hotel findHotel(long id) {
        return hotelRepository.findById(id)
            .orElseThrow(HotelNotFoundException::new);
    }

    private static HotelResponse toResponse(Hotel hotel) {
        return new HotelResponse(hotel.getId(), hotel.getName(), hotel.getAddress());
    }
}
