package com.example.hotel.hotel.service;

import com.example.hotel.hotel.controller.dto.CreateRoomRequest;
import com.example.hotel.hotel.controller.dto.RoomPageResponse;
import com.example.hotel.hotel.controller.dto.RoomResponse;
import com.example.hotel.hotel.controller.dto.RoomStatisticsPageResponse;
import com.example.hotel.hotel.controller.dto.RoomStatisticsResponse;
import com.example.hotel.hotel.controller.dto.UpdateRoomRequest;
import com.example.hotel.hotel.domain.Room;
import com.example.hotel.hotel.repository.HotelRepository;
import com.example.hotel.hotel.repository.RoomOccupancyProjection;
import com.example.hotel.hotel.repository.RoomRepository;
import com.example.hotel.hotel.repository.RoomReservationRepository;
import com.example.hotel.hotel.service.exception.EmptyRoomUpdateException;
import com.example.hotel.hotel.service.exception.HotelNotFoundException;
import com.example.hotel.hotel.service.exception.InvalidReservationDatesException;
import com.example.hotel.hotel.service.exception.RoomHasFutureReservationsException;
import com.example.hotel.hotel.service.exception.RoomHasReservationsException;
import com.example.hotel.hotel.service.exception.RoomNotFoundException;
import com.example.hotel.hotel.service.exception.RoomNumberAlreadyExistsException;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import lombok.RequiredArgsConstructor;

import static com.example.hotel.hotel.domain.RoomReservationState.RESERVED;
import static com.example.hotel.hotel.repository.RoomSpecifications.availableForDates;
import static com.example.hotel.hotel.repository.RoomSpecifications.excludingRooms;
import static java.time.temporal.ChronoUnit.DAYS;
import static java.util.Locale.ROOT;

@Service
@RequiredArgsConstructor
public class RoomService {
    private final RoomRepository roomRepository;
    private final HotelRepository hotelRepository;
    private final RoomReservationRepository reservationRepository;
    private final Clock clock;

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
    public RoomPageResponse listAvailableRooms(LocalDate startDate,
                                               LocalDate endDate,
                                               Long hotelId,
                                               int page,
                                               int size) {
        return findAvailableRooms(startDate, endDate, hotelId, List.of(), PageRequest.of(page, size, Sort.by("id")));
    }

    @Transactional(readOnly = true)
    public RoomPageResponse recommendAvailableRooms(LocalDate startDate,
                                                    LocalDate endDate,
                                                    Long hotelId,
                                                    int page,
                                                    int size) {
        return recommendAvailableRooms(startDate, endDate, hotelId, page, size, List.of());
    }

    @Transactional(readOnly = true)
    public RoomPageResponse recommendAvailableRooms(LocalDate startDate, LocalDate endDate, Long hotelId,
                                                    int page, int size, List<Long> excludedRoomIds) {
        return findAvailableRooms(startDate, endDate, hotelId, excludedRoomIds,
            PageRequest.of(page, size, Sort.by("timesBooked", "id")));
    }

    @Transactional(readOnly = true)
    public RoomStatisticsPageResponse getOccupancyStatistics(LocalDate startDate,
                                                             LocalDate endDate,
                                                             Long hotelId,
                                                             int page,
                                                             int size) {
        if (!startDate.isBefore(endDate)) {
            throw new InvalidReservationDatesException();
        }

        final var totalElements = hotelId == null
            ? roomRepository.count()
            : roomRepository.countByHotelId(hotelId);
        final var pageable = PageRequest.of(page, size);
        if (pageable.getOffset() >= totalElements) {
            return new RoomStatisticsPageResponse(List.of(), page, size, totalElements);
        }

        final var totalNights = DAYS.between(startDate, endDate); // totalNights is always >= 1
        final var statistics = roomRepository.findOccupancyStatistics(startDate, endDate, hotelId, RESERVED.name(), pageable)
            .stream()
            .map(occupancy -> toStatisticsResponse(occupancy, totalNights))
            .toList();
        return new RoomStatisticsPageResponse(statistics, page, size, totalElements);
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

        final var room = findRoomForUpdate(id);
        final var number = request.getNumber() == null
            ? room.getNumber()
            : request.getNumber();
        final var available = request.getAvailable() == null
            ? room.isAvailable()
            : request.getAvailable();
        if (!available && reservationExists(id)) {
            throw new RoomHasFutureReservationsException();
        }
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
        final var room = findRoomForUpdate(id);
        if (reservationRepository.existsByRoomId(id)) {
            throw new RoomHasReservationsException();
        }
        roomRepository.delete(room);
    }

    private RoomPageResponse findAvailableRooms(LocalDate startDate, LocalDate endDate, Long hotelId, List<Long> excludedRoomIds, Pageable pageable) {
        if (!startDate.isBefore(endDate)) {
            throw new InvalidReservationDatesException();
        }

        final var specification = availableForDates(startDate, endDate, hotelId).and(excludingRooms(excludedRoomIds));
        final var totalElements = roomRepository.count(specification);
        if (pageable.getOffset() >= totalElements) {
            return new RoomPageResponse(List.of(), pageable.getPageNumber(), pageable.getPageSize(), totalElements);
        }

        final var rooms = roomRepository.findBy(specification, query -> query.slice(pageable))
            .getContent().stream()
            .map(RoomService::toResponse)
            .toList();
        return new RoomPageResponse(rooms, pageable.getPageNumber(), pageable.getPageSize(), totalElements);
    }

    private Room findRoom(long id) {
        return roomRepository.findById(id)
            .orElseThrow(RoomNotFoundException::new);
    }

    private boolean reservationExists(long id) {
        return reservationRepository.existsByRoomIdAndStateAndEndDateGreaterThan(id, RESERVED, LocalDate.now(clock));
    }

    private Room findRoomForUpdate(long id) {
        return roomRepository.findByIdForUpdate(id)
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

    private static RoomStatisticsResponse toStatisticsResponse(RoomOccupancyProjection occupancy, long totalNights) {
        final var bookedNights = occupancy.getBookedNights();
        return new RoomStatisticsResponse(
            occupancy.getRoomId(),
            occupancy.getTimesBooked(),
            bookedNights,
            totalNights,
            (double) bookedNights / totalNights
        );
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
