package com.example.hotel.hotel.repository;

import com.example.hotel.hotel.domain.Hotel;
import com.example.hotel.hotel.domain.Room;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hibernate.Hibernate.isInitialized;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DataJpaTest
class RoomRepositoryTest {
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldPersistRoomWhenHotelExists(boolean available) {
        final var hotel = hotelRepository.saveAndFlush(new Hotel("City Hotel", "Central Street, 15"));
        final var room = new Room(hotel, "101 A", available);

        final var savedRoom = roomRepository.saveAndFlush(room);
        entityManager.clear();
        final var persistedRoom = roomRepository.findById(savedRoom.getId()).orElseThrow();

        assertThat(persistedRoom.getId()).isPositive();
        assertThat(persistedRoom.getNumber()).isEqualTo("101 A");
        assertThat(persistedRoom.isAvailable()).isEqualTo(available);
        assertThat(persistedRoom.getTimesBooked()).isZero();
        assertThat(isInitialized(persistedRoom.getHotel())).isFalse();
        assertThat(persistedRoom.getHotel().getId()).isEqualTo(hotel.getId());
        assertThat(persistedRoom.getHotel().getName()).isEqualTo("City Hotel");
    }

    @Test
    void shouldPersistRoomWhenNumberLengthReachesLimit() {
        final var hotel = hotelRepository.saveAndFlush(new Hotel("City Hotel", "Central Street, 15"));
        final var room = new Room(hotel, "A".repeat(32), true);

        final var savedRoom = roomRepository.saveAndFlush(room);
        entityManager.clear();
        final var persistedRoom = roomRepository.findById(savedRoom.getId()).orElseThrow();

        assertThat(persistedRoom.getNumber()).hasSize(32);
    }

    @Test
    void shouldRejectRoomWhenNumberLengthExceedsLimit() {
        final var hotel = hotelRepository.saveAndFlush(new Hotel("City Hotel", "Central Street, 15"));
        final var room = new Room(hotel, "A".repeat(33), true);

        final var exception = catchThrowable(() -> roomRepository.saveAndFlush(room));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectRoomWhenNumberAlreadyExistsInHotel() {
        final var hotel = hotelRepository.saveAndFlush(new Hotel("City Hotel", "Central Street, 15"));
        roomRepository.saveAndFlush(new Room(hotel, "101", true));
        final var duplicateRoom = new Room(hotel, "101", false);

        final var exception = catchThrowable(() -> roomRepository.saveAndFlush(duplicateRoom));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("UK_ROOMS_HOTEL_NUMBER");
    }

    @Test
    void shouldRejectRoomWhenHotelDoesNotExist() {
        final var hotel = entityManager.getReference(Hotel.class, Long.MAX_VALUE);
        final var room = new Room(hotel, "101", true);

        final var exception = catchThrowable(() -> roomRepository.saveAndFlush(room));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("FK_ROOMS_HOTEL");
    }

    @Test
    void shouldRejectRoomWhenHotelIsNull() {
        final var exception = catchThrowable(() -> jdbcTemplate.update(
            "INSERT INTO rooms (hotel_id, number, available) VALUES (?, ?, ?)", null, "101", true));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @MethodSource("missingRoomValues")
    void shouldRejectRoomWhenRequiredValueIsNull(String number, Boolean available, Long timesBooked) {
        final var hotel = hotelRepository.saveAndFlush(new Hotel("City Hotel", "Central Street, 15"));

        final var exception = catchThrowable(() -> jdbcTemplate.update(
            "INSERT INTO rooms (hotel_id, number, available, times_booked) VALUES (?, ?, ?, ?)",
            hotel.getId(), number, available, timesBooked));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldInitializeCounterWhenInsertOmitsTimesBooked() {
        final var hotel = hotelRepository.saveAndFlush(new Hotel("City Hotel", "Central Street, 15"));

        jdbcTemplate.update("INSERT INTO rooms (hotel_id, number, available) VALUES (?, ?, ?)",
            hotel.getId(), "101", true);
        final var timesBooked = jdbcTemplate.queryForObject(
            "SELECT times_booked FROM rooms WHERE hotel_id = ? AND number = ?", Long.class, hotel.getId(), "101");

        assertThat(timesBooked).isZero();
    }

    @Test
    void shouldRejectHotelDeletionWhenRoomsStillExist() {
        final var hotel = hotelRepository.saveAndFlush(new Hotel("City Hotel", "Central Street, 15"));
        roomRepository.saveAndFlush(new Room(hotel, "101", true));
        entityManager.clear();

        final var exception = catchThrowable(() -> {
            hotelRepository.deleteById(hotel.getId());
            hotelRepository.flush();
        });

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("FK_ROOMS_HOTEL");
    }

    @Test
    void shouldKeepHotelWhenRoomIsDeleted() {
        final var hotel = hotelRepository.saveAndFlush(new Hotel("City Hotel", "Central Street, 15"));
        final var room = roomRepository.saveAndFlush(new Room(hotel, "101", true));

        roomRepository.delete(room);
        roomRepository.flush();
        entityManager.clear();

        assertThat(roomRepository.findById(room.getId())).isEmpty();
        assertThat(hotelRepository.findById(hotel.getId())).isPresent();
    }

    @Test
    void shouldDeleteHotelWhenLastRoomIsDeleted() {
        final var hotel = hotelRepository.saveAndFlush(new Hotel("City Hotel", "Central Street, 15"));
        final var room = roomRepository.saveAndFlush(new Room(hotel, "101", true));

        roomRepository.delete(room);
        roomRepository.flush();
        hotelRepository.delete(hotel);
        hotelRepository.flush();
        entityManager.clear();

        assertThat(hotelRepository.findById(hotel.getId())).isEmpty();
    }

    private static Stream<Arguments> missingRoomValues() {
        return Stream.of(
            arguments(null, true, 0L),
            arguments("101", null, 0L),
            arguments("101", true, null)
        );
    }
}
