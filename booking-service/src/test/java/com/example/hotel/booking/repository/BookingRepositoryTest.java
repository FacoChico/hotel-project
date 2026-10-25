package com.example.hotel.booking.repository;

import com.example.hotel.booking.domain.Booking;
import com.example.hotel.booking.domain.BookingReleaseReason;
import com.example.hotel.booking.domain.BookingStatus;
import com.example.hotel.booking.domain.User;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import static com.example.hotel.booking.domain.BookingStatus.PENDING;
import static com.example.hotel.booking.domain.UserRole.USER;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hibernate.Hibernate.isInitialized;
import static org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType.H2;

@DataJpaTest
class BookingRepositoryTest {
    private static final UUID REQUEST_ID = UUID.fromString("8295a419-7011-48c8-805d-dc186959da80");
    private static final LocalDate START_DATE = LocalDate.of(2027, 1, 10);
    private static final LocalDate END_DATE = LocalDate.of(2027, 1, 12);
    private static final Instant CREATED_AT = Instant.parse("2027-01-01T10:00:00Z");
    private static final Instant DEADLINE_AT = CREATED_AT.plusSeconds(30);

    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @CsvSource({
        "false, , 42",
        "true, 11, "
    })
    void shouldPersistPendingBookingWhenSelectionParametersAreProvided(boolean autoSelect, Long hotelId, Long roomId) {
        final var user = createUser("hotel_guest");
        final var booking = new Booking(user, REQUEST_ID, autoSelect, hotelId, roomId,
            START_DATE, END_DATE, CREATED_AT, DEADLINE_AT);

        final var savedBooking = bookingRepository.saveAndFlush(booking);
        entityManager.clear();
        final var persistedBooking = bookingRepository.findById(savedBooking.getId()).orElseThrow();

        assertThat(persistedBooking.getId()).isPositive();
        assertThat(persistedBooking.getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(persistedBooking.isAutoSelect()).isEqualTo(autoSelect);
        assertThat(persistedBooking.getHotelId()).isEqualTo(hotelId);
        assertThat(persistedBooking.getRoomId()).isEqualTo(roomId);
        assertThat(persistedBooking.getStartDate()).isEqualTo(START_DATE);
        assertThat(persistedBooking.getEndDate()).isEqualTo(END_DATE);
        assertThat(persistedBooking.getStatus()).isEqualTo(PENDING);
        assertThat(persistedBooking.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(persistedBooking.getDeadlineAt()).isEqualTo(DEADLINE_AT);
        assertThat(persistedBooking.isReleasePending()).isFalse();
        assertThat(persistedBooking.getReleaseReason()).isNull();
        assertThat(persistedBooking.getVersion()).isZero();
        assertThat(isInitialized(persistedBooking.getUser())).isFalse();
        assertThat(persistedBooking.getUser().getId()).isEqualTo(user.getId());
        assertThat(persistedBooking.getUser().getUsername()).isEqualTo("hotel_guest");
    }

    @ParameterizedTest
    @EnumSource(BookingStatus.class)
    void shouldReadStatusWhenBookingIsStoredAsString(BookingStatus status) {
        final var booking = createBooking(createUser("hotel_guest"), REQUEST_ID);

        jdbcTemplate.update("UPDATE bookings SET status = ? WHERE id = ?", status.name(), booking.getId());
        entityManager.clear();
        final var persistedBooking = bookingRepository.findById(booking.getId()).orElseThrow();

        assertThat(persistedBooking.getStatus()).isEqualTo(status);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM bookings WHERE id = ?", String.class, booking.getId())).isEqualTo(status.name());
    }

    @ParameterizedTest
    @EnumSource(BookingReleaseReason.class)
    void shouldRetainReleaseTaskWhenCancelledBookingIsReloaded(BookingReleaseReason reason) {
        final var booking = createBooking(createUser("hotel_guest"), REQUEST_ID);

        jdbcTemplate.update("""
            UPDATE bookings SET status = 'CANCELLED', release_pending = TRUE, release_reason = ? WHERE id = ?
            """, reason.name(), booking.getId());
        entityManager.clear();
        final var persistedBooking = bookingRepository.findById(booking.getId()).orElseThrow();

        assertThat(persistedBooking.isReleasePending()).isTrue();
        assertThat(persistedBooking.getReleaseReason()).isEqualTo(reason);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT release_reason FROM bookings WHERE id = ?", String.class, booking.getId()))
            .isEqualTo(reason.name());
    }

    @Test
    void shouldFindAcceptedRequestWhenUserAndRequestIdMatch() {
        final var firstUser = createUser("first_guest");
        final var secondUser = createUser("second_guest");
        final var firstBooking = createBooking(firstUser, REQUEST_ID);
        final var secondBooking = createBooking(secondUser, REQUEST_ID);
        final var anotherBooking = createBooking(firstUser, randomUUID());
        entityManager.clear();

        final var firstMatch = bookingRepository.findByUserIdAndRequestId(firstUser.getId(), REQUEST_ID);
        final var secondMatch = bookingRepository.findByUserIdAndRequestId(secondUser.getId(), REQUEST_ID);
        final var anotherMatch = bookingRepository.findByUserIdAndRequestId(firstUser.getId(), anotherBooking.getRequestId());

        assertThat(firstMatch).get().extracting(Booking::getId).isEqualTo(firstBooking.getId());
        assertThat(secondMatch).get().extracting(Booking::getId).isEqualTo(secondBooking.getId());
        assertThat(anotherMatch).get().extracting(Booking::getId).isEqualTo(anotherBooking.getId());
    }

    @Test
    void shouldReturnEmptyWhenRequestDoesNotBelongToUser() {
        final var owner = createUser("hotel_guest");
        final var anotherUser = createUser("another_guest");
        createBooking(owner, REQUEST_ID);

        final var wrongUser = bookingRepository.findByUserIdAndRequestId(anotherUser.getId(), REQUEST_ID);
        final var wrongRequest = bookingRepository.findByUserIdAndRequestId(owner.getId(), randomUUID());

        assertThat(wrongUser).isEmpty();
        assertThat(wrongRequest).isEmpty();
        assertThat(bookingRepository.existsByUserId(owner.getId())).isTrue();
        assertThat(bookingRepository.existsByUserId(anotherUser.getId())).isFalse();
    }

    @ParameterizedTest
    @EnumSource(BookingStatus.class)
    void shouldRejectDuplicateWhenUserHasAcceptedRequest(BookingStatus existingStatus) {
        final var user = createUser("hotel_guest");
        final var existingBooking = createBooking(user, REQUEST_ID);
        jdbcTemplate.update("UPDATE bookings SET status = ? WHERE id = ?", existingStatus.name(), existingBooking.getId());
        final var duplicate = new Booking(user, REQUEST_ID, false, null, 43L,
            END_DATE, END_DATE.plusDays(1), CREATED_AT, DEADLINE_AT);

        final var exception = catchThrowable(() -> bookingRepository.saveAndFlush(duplicate));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("UK_BOOKINGS_USER_REQUEST");
    }

    @Test
    void shouldRejectBookingWhenUserDoesNotExist() {
        final var missingUser = entityManager.getReference(User.class, Long.MAX_VALUE);
        final var booking = new Booking(missingUser, REQUEST_ID, false, null, 42L,
            START_DATE, END_DATE, CREATED_AT, DEADLINE_AT);

        final var exception = catchThrowable(() -> bookingRepository.saveAndFlush(booking));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("FK_BOOKINGS_USER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_id", "request_id", "auto_select", "start_date", "end_date",
        "status", "created_at", "deadline_at", "release_pending", "version"})
    void shouldRejectBookingWhenRequiredColumnIsNull(String column) {
        final var booking = createBooking(createUser("hotel_guest"), REQUEST_ID);

        final var exception = catchThrowable(() -> jdbcTemplate.update(
            "UPDATE bookings SET " + column + " = NULL WHERE id = ?", booking.getId()));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2027-01-10", "2027-01-09"})
    void shouldRejectBookingWhenEndDateDoesNotFollowStartDate(String endDate) {
        final var booking = new Booking(createUser("hotel_guest"), REQUEST_ID, false, null, 42L,
            START_DATE, LocalDate.parse(endDate), CREATED_AT, DEADLINE_AT);

        final var exception = catchThrowable(() -> bookingRepository.saveAndFlush(booking));

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("CK_BOOKINGS_DATES");
    }

    @ParameterizedTest
    @EnumSource(BookingStatus.class)
    void shouldRejectUserDeletionWhenBookingHistoryExists(BookingStatus status) {
        final var user = createUser("hotel_guest");
        final var booking = createBooking(user, REQUEST_ID);
        jdbcTemplate.update("UPDATE bookings SET status = ? WHERE id = ?", status.name(), booking.getId());
        entityManager.clear();

        final var exception = catchThrowable(() -> {
            userRepository.deleteById(user.getId());
            userRepository.flush();
        });

        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("FK_BOOKINGS_USER");
    }

    @Test
    void shouldKeepUserWhenBookingIsDeleted() {
        final var user = createUser("hotel_guest");
        final var booking = createBooking(user, REQUEST_ID);

        bookingRepository.delete(booking);
        bookingRepository.flush();
        entityManager.clear();

        assertThat(bookingRepository.findById(booking.getId())).isEmpty();
        assertThat(userRepository.findById(user.getId())).isPresent();
    }

    @Test
    void shouldRejectStaleVersionWhenAnotherOperationHasChangedBooking() {
        final var booking = createBooking(createUser("hotel_guest"), REQUEST_ID);
        entityManager.clear();
        jdbcTemplate.update("""
            UPDATE bookings SET status = 'CANCELLED', release_pending = TRUE,
                release_reason = 'COMPENSATION', version = version + 1 WHERE id = ?
            """, booking.getId());

        final var exception = catchThrowable(() -> bookingRepository.saveAndFlush(booking));

        assertThat(exception).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void shouldPreserveUsersWhenBookingMigrationIsApplied() {
        final var database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(H2).build();
        try {
            // given
            Flyway.configure().dataSource(database).target("1").load().migrate();
            final var databaseJdbcTemplate = new JdbcTemplate(database);
            databaseJdbcTemplate.update("INSERT INTO users (id, username, password, role) VALUES (?, ?, ?, ?)",
                61L, "hotel_guest", "test-password-hash", "USER");

            // when
            Flyway.configure().dataSource(database).load().migrate();

            // then
            assertThat(databaseJdbcTemplate.queryForObject(
                "SELECT username FROM users WHERE id = 61", String.class)).isEqualTo("hotel_guest");
            assertThat(databaseJdbcTemplate.queryForObject(
                "SELECT password FROM users WHERE id = 61", String.class)).isEqualTo("test-password-hash");
            assertThat(databaseJdbcTemplate.queryForObject(
                "SELECT role FROM users WHERE id = 61", String.class)).isEqualTo("USER");
            assertThat(databaseJdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Long.class)).isZero();
        } finally {
            database.shutdown();
        }
    }

    private User createUser(String username) {
        return userRepository.saveAndFlush(new User(username, "test-password-hash", USER));
    }

    private Booking createBooking(User user, UUID requestId) {
        return bookingRepository.saveAndFlush(new Booking(user, requestId, false, null, 42L,
            START_DATE, END_DATE, CREATED_AT, DEADLINE_AT));
    }
}
