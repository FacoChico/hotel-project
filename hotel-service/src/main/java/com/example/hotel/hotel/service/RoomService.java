package com.example.hotel.hotel.service;

import com.example.hotel.hotel.controller.dto.CreateRoomRequest;
import com.example.hotel.hotel.controller.dto.RoomResponse;
import com.example.hotel.hotel.controller.dto.UpdateRoomRequest;
import com.example.hotel.hotel.domain.Room;
import com.example.hotel.hotel.repository.HotelRepository;
import com.example.hotel.hotel.repository.RoomRepository;
import com.example.hotel.hotel.service.exception.EmptyRoomUpdateException;
import com.example.hotel.hotel.service.exception.HotelNotFoundException;
import com.example.hotel.hotel.service.exception.RoomNotFoundException;
import com.example.hotel.hotel.service.exception.RoomNumberAlreadyExistsException;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import static java.util.Locale.ROOT;

@Service
@RequiredArgsConstructor
public class RoomService {
    private final RoomRepository roomRepository;
    private final HotelRepository hotelRepository;

    @Transactional
    public RoomResponse createRoom(CreateRoomRequest request) {
        final var hotel = hotelRepository.findById(request.hotelId())
            .orElseThrow(HotelNotFoundException::new);
        final var room = new Room(hotel, request.number(), request.available());

        try {
            roomRepository.saveAndFlush(room);
        } catch (DataIntegrityViolationException exception) {
            throw evaluatePersistenceException(exception);
        }
        return toResponse(room);
    }

    @Transactional(readOnly = true)
    public RoomResponse getRoom(long id) {
        return toResponse(findRoom(id));
    }

    @Transactional
    public RoomResponse updateRoom(long id, UpdateRoomRequest request) {
        if (request.getNumber() == null && request.getAvailable() == null) {
            throw new EmptyRoomUpdateException();
        }

        final var room = findRoom(id);
        final var number = request.getNumber() == null
            ? room.getNumber()
            : request.getNumber();
        final var available = request.getAvailable() == null
            ? room.isAvailable()
            : request.getAvailable();
        room.updateDetails(number, available);

        try {
            roomRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw evaluatePersistenceException(exception);
        }
        return toResponse(room);
    }

    @Transactional
    public void deleteRoom(long id) {
        roomRepository.delete(findRoom(id));
    }

    private Room findRoom(long id) {
        return roomRepository.findById(id)
            .orElseThrow(RoomNotFoundException::new);
    }

    private static RuntimeException evaluatePersistenceException(DataIntegrityViolationException exception) {
        if (exception.getCause() instanceof ConstraintViolationException constraint
            && constraint.getConstraintName() != null) {
            final var constraintName = constraint.getConstraintName().toLowerCase(ROOT);
            if (constraintName.contains("uk_rooms_hotel_number")) {
                return new RoomNumberAlreadyExistsException(exception);
            }
            if (constraintName.contains("fk_rooms_hotel")) {
                return new HotelNotFoundException(exception);
            }
        }
        return exception;
    }

    private static RoomResponse toResponse(Room room) {
        return new RoomResponse(
            room.getId(),
            room.getHotel().getId(),
            room.getNumber(),
            room.isAvailable(),
            room.getTimesBooked()
        );
    }
}
