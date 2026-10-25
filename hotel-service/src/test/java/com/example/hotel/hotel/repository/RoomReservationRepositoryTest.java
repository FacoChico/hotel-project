package com.example.hotel.hotel.repository;

import com.example.hotel.hotel.domain.Hotel;
import com.example.hotel.hotel.domain.Room;
import com.example.hotel.hotel.domain.RoomReservation;
import com.example.hotel.hotel.domain.RoomReservationState;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;

import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import static com.example.hotel.hotel.domain.RoomReservationState.RELEASED;
import static com.example.hotel.hotel.domain.RoomReservationState.RESERVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hibernate.Hibernate.isInitialized;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType.H2;

@DataJpaTest
class RoomReservationRepositoryTest {
    private static final UUID REQUEST_ID = UUID.fromString("c7df6cee-2bd9-43b5-af66-54256c622a34");
    private static final LocalDate START_DATE = LocalDate.of(2027, 1, 10);
    private static final LocalDate END_DATE = LocalDate.of(2027, 1, 12);
    private static final String INSERT_RESERVATION = """
        INSERT INTO room_reservations (room_id, booking_id, request_id, start_date, end_date, state)
        VALUES (?, ?, ?, ?, ?, ?)
        """;

    @Autowired
    private RoomReservationRepository reservationRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @EnumSource(RoomReservationState.class)
    void shouldPersistReservationWhenDatesAreProvided(RoomReservationState state) {
        final var room = createRoom("101");
        final var reservation = new RoomReservation(room, 42L, REQUEST_ID, START_DATE, END_DATE, state);

        final var savedReservation = reservationRepository.saveAndFlush(reservation);
        entityManager.clear();
        final var persistedReservation = reservationRepository.findById(savedReservation.getId()).orElseThrow();

        assertThat(persistedReservation.getId()).isPositive();
        assertThat(persistedReservation.getBookingId()).isEqualTo(42L);
        assertThat(persistedReservation.getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(persistedReservation.getStartDate()).isEqualTo(START_DATE);
        assertThat(persistedReservation.getEndDate()).isEqualTo(END_DATE);
        assertThat(persistedReservation.getState()).isEqualTo(state);
        assertThat(isInitialized(persistedReservation.getRoom())).isFalse();
        assertThat(persistedReservation.getRoom().getId()).isEqualTo(room.getId());
        assertThat(persistedReservation.getRoom().getNumber()).isEqualTo("101");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT state FROM room_reservations WHERE id = ?", String.class, savedReservation.getId()))
            .isEqualTo(state.name());
    }

    @Test
    void shouldPersistReleaseWhenConfirmationHasNotArrived() {
        final var room = createRoom("101");
        final var reservation = new RoomReservation(room, 42L, REQUEST_ID, null, null, RELEASED);

        final var savedReservation = reservationRepository.saveAndFlush(reservation);
        entityManager.clear();
        final var persistedReservation = reservationRepository.findById(savedReservation.getId()).orElseThrow();

        assertThat(persistedReservation.getBookingId()).isEqualTo(42L);
        assertThat(persistedReservation.getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(persistedReservation.getState()).isEqualTo(RELEASED);
        assertThat(persistedReservation.getStartDate()).isNull();
        assertThat(persistedReservation.getEndDate()).isNull();
    }

    @ParameterizedTest
    @EnumSource(RoomReservationState.class)
    void shouldFindReservationWhenBookingAndRoomMatch(RoomReservationState state) {
        final var room = createRoom("101");
        final var anotherRoom = roomRepository.saveAndFlush(new Room(room.getHotel(), "102", true));
        final var expectedReservation = reservationRepository.saveAndFlush(
            new RoomReservation(room, 42L, REQUEST_ID, START_DATE, END_DATE, state));
        reservationRepository.saveAndFlush(
            new RoomReservation(room, 43L, REQUEST_ID, END_DATE, END_DATE.plusDays(1), RESERVED));
        reservationRepository.saveAndFlush(
            new RoomReservation(anotherRoom, 42L, REQUEST_ID, START_DATE, END_DATE, RESERVED));
        entityManager.clear();

        final var reservation = reservationRepository.findByBookingIdAndRoomId(42L, room.getId());

        assertThat(reservation).isPresent();
        assertThat(reservation.orElseThrow().getId()).isEqualTo(expectedReservation.getId());
        assertThat(reservation.orElseThrow().getState()).isEqualTo(state);
    }

    @Test
    void shouldReturnEmptyWhenReservationPairDoesNotExist() {
        final var room = createRoom("101");
        reservationRepository.saveAndFlush(
            new RoomReservation(room, 42L, REQUEST_ID, START_DATE, END_DATE, RESERVED));

        final var missingBooking = reservationRepository.findByBookingIdAndRoomId(43L, room.getId());
        final var missingRoom = reservationRepository.findByBookingIdAndRoomId(42L, Long.MAX_VALUE);

        assertThat(missingBooking).isEmpty();
        assertThat(missingRoom).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(RoomReservationState.class)
    void shouldRejectDuplicateWhenBookingAndRoomAlreadyHaveHistory(RoomReservationState existingState) {
        final var room = createRoom("101");
        reservationRepository.saveAndFlush(
            new RoomReservation(room, 42L, REQUEST_ID, START_DATE, END_DATE, existingState));
        final var duplicate = new RoomReservation(room, 42L,
            UUID.fromString("683d1234-b36a-4a25-a77b-12b789480001"), END_DATE, END_DATE.plusDays(1), RESERVED);

        final var exception = catchThrowable(() -> reservationRepository.saveAndFlush(duplicate));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("UK_ROOM_RESERVATIONS_BOOKING_ROOM");
    }

    @Test
    void shouldRejectReservationWhenRoomDoesNotExist() {
        final var missingRoom = entityManager.getReference(Room.class, Long.MAX_VALUE);
        final var reservation = new RoomReservation(missingRoom, 42L, REQUEST_ID, START_DATE, END_DATE, RESERVED);

        final var exception = catchThrowable(() -> reservationRepository.saveAndFlush(reservation));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("FK_ROOM_RESERVATIONS_ROOM");
    }

    @Test
    void shouldRejectReservationWhenRoomIsNull() {
        final var exception = catchThrowable(() -> jdbcTemplate.update(
            INSERT_RESERVATION, null, 42L, REQUEST_ID, START_DATE, END_DATE, "RESERVED"));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @MethodSource("missingRequiredValues")
    void shouldRejectReservationWhenRequiredValueIsNull(Long bookingId, UUID requestId, String state) {
        final var room = createRoom("101");

        final var exception = catchThrowable(() -> jdbcTemplate.update(
            INSERT_RESERVATION, room.getId(), bookingId, requestId, START_DATE, END_DATE, state));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidReservationDates")
    void shouldRejectReservationWhenDatesViolateStateContract(LocalDate startDate,
                                                              LocalDate endDate, String state) {
        final var room = createRoom("101");

        final var exception = catchThrowable(() -> jdbcTemplate.update(
            INSERT_RESERVATION, room.getId(), 42L, REQUEST_ID, startDate, endDate, state));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("CK_ROOM_RESERVATIONS_DATES");
    }

    @ParameterizedTest
    @CsvSource({
        "RESERVED, true",
        "RELEASED, true",
        "RELEASED, false"
    })
    void shouldRejectRoomDeletionWhenReservationHistoryExists(RoomReservationState state, boolean hasDates) {
        final var room = createRoom("101");
        reservationRepository.saveAndFlush(new RoomReservation(room, 42L, REQUEST_ID,
            hasDates
                ? START_DATE
                : null, hasDates
            ? END_DATE
            : null, state));
        entityManager.clear();

        final var exception = catchThrowable(() -> {
            roomRepository.deleteById(room.getId());
            roomRepository.flush();
        });

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("FK_ROOM_RESERVATIONS_ROOM");
    }

    @Test
    void shouldKeepRoomWhenReservationIsDeleted() {
        final var room = createRoom("101");
        final var reservation = reservationRepository.saveAndFlush(
            new RoomReservation(room, 42L, REQUEST_ID, START_DATE, END_DATE, RESERVED));

        reservationRepository.delete(reservation);
        reservationRepository.flush();
        entityManager.clear();

        assertThat(reservationRepository.findById(reservation.getId())).isEmpty();
        assertThat(roomRepository.findById(room.getId())).isPresent();
    }

    @Test
    void shouldPreserveHotelsAndRoomsWhenReservationMigrationIsApplied() {
        final var database = new EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(H2)
            .build();
        try {
            // given
            Flyway.configure()
                .dataSource(database)
                .target("1")
                .load()
                .migrate();
            final var databaseJdbcTemplate = new JdbcTemplate(database);
            databaseJdbcTemplate.update("INSERT INTO hotels (id, name, address) VALUES (?, ?, ?)",
                61L, "City Hotel", "Central Street, 15");
            databaseJdbcTemplate.update(
                "INSERT INTO rooms (id, hotel_id, number, available, times_booked) VALUES (?, ?, ?, ?, ?)",
                62L, 61L, "101", false, 7L);

            // when
            Flyway.configure()
                .dataSource(database)
                .load()
                .migrate();

            // then
            assertThat(databaseJdbcTemplate.queryForObject(
                "SELECT name FROM hotels WHERE id = 61", String.class)).isEqualTo("City Hotel");
            assertThat(databaseJdbcTemplate.queryForObject(
                "SELECT address FROM hotels WHERE id = 61", String.class)).isEqualTo("Central Street, 15");
            assertThat(databaseJdbcTemplate.queryForObject(
                "SELECT hotel_id FROM rooms WHERE id = 62", Long.class)).isEqualTo(61L);
            assertThat(databaseJdbcTemplate.queryForObject(
                "SELECT number FROM rooms WHERE id = 62", String.class)).isEqualTo("101");
            assertThat(databaseJdbcTemplate.queryForObject(
                "SELECT available FROM rooms WHERE id = 62", Boolean.class)).isFalse();
            assertThat(databaseJdbcTemplate.queryForObject(
                "SELECT times_booked FROM rooms WHERE id = 62", Long.class)).isEqualTo(7L);
            assertThat(databaseJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM room_reservations", Long.class)).isZero();
        } finally {
            database.shutdown();
        }
    }

    private Room createRoom(String number) {
        final var hotel = hotelRepository.saveAndFlush(new Hotel("City Hotel", "Central Street, 15"));
        return roomRepository.saveAndFlush(new Room(hotel, number, true));
    }

    private static Stream<Arguments> missingRequiredValues() {
        return Stream.of(
            arguments(null, REQUEST_ID, "RESERVED"),
            arguments(42L, null, "RESERVED"),
            arguments(42L, REQUEST_ID, null)
        );
    }

    private static Stream<Arguments> invalidReservationDates() {
        return Stream.of(
            arguments(null, null, "RESERVED"),
            arguments(null, END_DATE, "RESERVED"),
            arguments(START_DATE, null, "RESERVED"),
            arguments(START_DATE, START_DATE, "RESERVED"),
            arguments(END_DATE, START_DATE, "RESERVED"),
            arguments(null, END_DATE, "RELEASED"),
            arguments(START_DATE, null, "RELEASED"),
            arguments(START_DATE, START_DATE, "RELEASED"),
            arguments(END_DATE, START_DATE, "RELEASED")
        );
    }
}
