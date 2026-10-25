package com.example.hotel.hotel.repository;

import com.example.hotel.hotel.domain.Hotel;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DataJpaTest
class HotelRepositoryTest {
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldApplyAllMigrationsWhenSchemaIsInitialized() {
        final var migrationInfo = flyway.info();

        final var appliedMigrations = migrationInfo.applied();
        final var pendingMigrations = migrationInfo.pending();

        assertThat(appliedMigrations)
            .extracting(MigrationInfo::getScript)
            .containsExactly("V1__create_hotels_and_rooms.sql", "V2__create_room_reservations.sql");
        assertThat(pendingMigrations).isEmpty();
    }

    @Test
    void shouldPersistHotelWhenDetailsAreProvided() {
        final var hotel = new Hotel("City Hotel", "Central Street, 15");

        final var savedHotel = hotelRepository.saveAndFlush(hotel);
        entityManager.clear();
        final var persistedHotel = hotelRepository.findById(savedHotel.getId()).orElseThrow();

        assertThat(persistedHotel.getId()).isPositive();
        assertThat(persistedHotel.getName()).isEqualTo("City Hotel");
        assertThat(persistedHotel.getAddress()).isEqualTo("Central Street, 15");
    }

    @Test
    void shouldPersistHotelWhenTextLengthsReachLimits() {
        final var hotel = new Hotel("N".repeat(200), "A".repeat(500));

        final var savedHotel = hotelRepository.saveAndFlush(hotel);
        entityManager.clear();
        final var persistedHotel = hotelRepository.findById(savedHotel.getId()).orElseThrow();

        assertThat(persistedHotel.getName()).hasSize(200);
        assertThat(persistedHotel.getAddress()).hasSize(500);
    }

    @ParameterizedTest
    @CsvSource({"201, 10", "10, 501"})
    void shouldRejectHotelWhenTextLengthExceedsLimit(int nameLength, int addressLength) {
        final var name = "N".repeat(nameLength);
        final var address = "A".repeat(addressLength);

        final var exception = catchThrowable(() -> jdbcTemplate.update(
            "INSERT INTO hotels (name, address) VALUES (?, ?)", name, address));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @MethodSource("missingHotelDetails")
    void shouldRejectHotelWhenRequiredDetailIsNull(String name, String address) {
        final var exception = catchThrowable(() -> jdbcTemplate.update(
            "INSERT INTO hotels (name, address) VALUES (?, ?)", name, address));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static Stream<Arguments> missingHotelDetails() {
        return Stream.of(arguments(null, "Address"), arguments("Hotel", null));
    }
}
