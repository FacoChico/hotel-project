package com.example.hotel.booking.repository;

import com.example.hotel.booking.domain.Booking;
import com.example.hotel.booking.domain.BookingStatus;
import com.example.hotel.booking.domain.User;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.EntityManager;

import static com.example.hotel.booking.domain.BookingStatus.CANCELLED;
import static com.example.hotel.booking.domain.BookingStatus.PENDING;
import static com.example.hotel.booking.domain.UserRole.USER;
import static java.time.ZoneOffset.UTC;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType.H2;

@DataJpaTest
class BookingRecoveryRepositoryTest {
    private static final Instant NOW = Instant.parse("2027-01-10T12:00:00Z");
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSelectOldestTasksWhenBatchSizeLimitsCandidates() {
        final var user = userRepository.saveAndFlush(new User("hotel_guest", "test-password-hash", USER));
        final var later = createBooking(user, NOW.minusSeconds(1));
        final var olderFirst = createBooking(user, NOW.minusSeconds(10));
        final var olderSecond = createBooking(user, NOW.minusSeconds(10));
        createBooking(user, NOW.plusSeconds(1));

        final var firstBatch = bookingRepository.findRecoveryCandidates(NOW, PENDING, CANCELLED, PageRequest.of(0, 2));
        final var nextPage = bookingRepository.findRecoveryCandidates(NOW, PENDING, CANCELLED, PageRequest.of(1, 2));

        assertThat(firstBatch).containsExactly(olderFirst.getId(), olderSecond.getId());
        assertThat(nextPage).containsExactly(later.getId());
    }

    @ParameterizedTest
    @CsvSource({
        "PENDING,false",
        "CANCELLED,true"
    })
    void shouldIncludeCandidateWhenOperationIsUnfinished(BookingStatus status, boolean releasePending) {
        final var user = userRepository.saveAndFlush(new User("hotel_guest", "test-password-hash", USER));
        final var booking = createBooking(user, NOW);
        jdbcTemplate.update("UPDATE bookings SET status = ?, release_pending = ? WHERE id = ?",
            status.name(), releasePending, booking.getId());
        entityManager.clear();

        final var candidates = bookingRepository.findRecoveryCandidates(NOW, PENDING, CANCELLED, PageRequest.of(0, 20));

        assertThat(candidates).containsExactly(booking.getId());
    }

    @ParameterizedTest
    @CsvSource({
        "CANCELLED,false",
        "CONFIRMED,false"
    })
    void shouldExcludeCandidateWhenOperationIsFinished(BookingStatus status, boolean releasePending) {
        final var user = userRepository.saveAndFlush(new User("hotel_guest", "test-password-hash", USER));
        final var booking = createBooking(user, NOW);
        jdbcTemplate.update("UPDATE bookings SET status = ?, release_pending = ? WHERE id = ?",
            status.name(), releasePending, booking.getId());
        entityManager.clear();

        final var candidates = bookingRepository.findRecoveryCandidates(NOW, PENDING, CANCELLED, PageRequest.of(0, 20));

        assertThat(candidates).isEmpty();
    }

    @Test
    void shouldRetainNewRecoveryTimeWhenClaimIsPersisted() {
        final var user = userRepository.saveAndFlush(new User("hotel_guest", "test-password-hash", USER));
        final var booking = createBooking(user, NOW);

        final var claimed = booking.claimRecovery(NOW, NOW.plusSeconds(5));
        bookingRepository.flush();
        entityManager.clear();
        final var persisted = bookingRepository.findById(booking.getId()).orElseThrow();
        final var candidates = bookingRepository.findRecoveryCandidates(NOW, PENDING, CANCELLED, PageRequest.of(0, 20));

        assertThat(claimed).isTrue();
        assertThat(persisted.getStatus()).isEqualTo(CANCELLED);
        assertThat(persisted.isReleasePending()).isTrue();
        assertThat(persisted.getNextRecoveryAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(candidates).isEmpty();
    }

    @Test
    void shouldBackfillOnlyUnfinishedOperationsWhenRecoveryMigrationIsApplied() {
        final var database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(H2).build();
        try {
            // given
            Flyway.configure().dataSource(database).target("2").load().migrate();
            final var databaseJdbcTemplate = new JdbcTemplate(database);
            databaseJdbcTemplate.update("INSERT INTO users (id, username, password, role) VALUES (61, ?, ?, ?)",
                "hotel_guest", "test-password-hash", "USER");
            insertPreviousBooking(databaseJdbcTemplate, 1L, "PENDING", false, null);
            insertPreviousBooking(databaseJdbcTemplate, 2L, "CONFIRMED", false, null);
            insertPreviousBooking(databaseJdbcTemplate, 3L, "CANCELLED", true, "COMPENSATION");
            insertPreviousBooking(databaseJdbcTemplate, 4L, "CANCELLED", true, "USER_CANCELLATION");
            insertPreviousBooking(databaseJdbcTemplate, 5L, "CANCELLED", false, "COMPENSATION");

            // when
            Flyway.configure().dataSource(database).load().migrate();

            // then
            assertThat(databaseJdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE next_recovery_at IS NOT NULL ORDER BY id", Long.class))
                .containsExactly(1L, 3L, 4L);
            assertThat(databaseJdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bookings WHERE next_recovery_at = deadline_at
                """, Long.class)).isEqualTo(3);
            assertThat(databaseJdbcTemplate.queryForObject(
                "SELECT release_reason FROM bookings WHERE id = 4", String.class)).isEqualTo("USER_CANCELLATION");
            assertThat(databaseJdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Long.class)).isEqualTo(5);
            assertThat(databaseJdbcTemplate.queryForObject(
                "SELECT username FROM users WHERE id = 61", String.class)).isEqualTo("hotel_guest");
        } finally {
            database.shutdown();
        }
    }

    private Booking createBooking(User user, Instant deadline) {
        return bookingRepository.saveAndFlush(new Booking(user, randomUUID(), false, null, 42L,
            LocalDate.ofInstant(NOW, UTC), LocalDate.ofInstant(NOW, UTC).plusDays(1), NOW.minusSeconds(30), deadline));
    }

    private static void insertPreviousBooking(JdbcTemplate jdbcTemplate, long id, String status,
                                              boolean releasePending, String reason) {
        jdbcTemplate.update("""
            INSERT INTO bookings (id, user_id, request_id, auto_select, room_id, start_date, end_date,
                status, created_at, deadline_at, release_pending, release_reason, version)
            VALUES (?, 61, ?, FALSE, 42, DATE '2027-01-10', DATE '2027-01-11', ?, ?, ?, ?, ?, 0)
            """, id, randomUUID(), status, NOW.minusSeconds(30).atOffset(UTC), NOW.atOffset(UTC), releasePending, reason);
    }
}
