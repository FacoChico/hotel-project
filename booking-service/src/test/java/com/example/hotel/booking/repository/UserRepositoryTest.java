package com.example.hotel.booking.repository;

import com.example.hotel.booking.domain.User;
import com.example.hotel.booking.domain.UserRole;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import jakarta.persistence.EntityManager;

import static com.example.hotel.booking.domain.UserRole.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@DataJpaTest
class UserRepositoryTest {
    private static final String PASSWORD_HASH = "test-password-hash";
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private Flyway flyway;

    @Test
    void shouldHaveUserSchemaWhenMigrationsAreApplied() {
        final var migrationInfo = flyway.info();

        final var appliedMigrations = migrationInfo.applied();
        final var pendingMigrations = migrationInfo.pending();

        assertThat(appliedMigrations)
            .extracting(MigrationInfo::getScript)
            .contains("V1__create_users.sql");
        assertThat(pendingMigrations).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void shouldPersistUser(UserRole role) {
        final var user = new User("hotel_guest", PASSWORD_HASH, role);

        final var savedUser = userRepository.saveAndFlush(user);
        entityManager.clear();
        final var persistedUser = userRepository.findById(savedUser.getId()).orElseThrow();
        final var persistedRole = entityManager.createNativeQuery("SELECT role FROM users WHERE id = :id", String.class)
            .setParameter("id", savedUser.getId())
            .getSingleResult();

        assertThat(savedUser.getId()).isPositive();
        assertThat(persistedUser.getUsername()).isEqualTo("hotel_guest");
        assertThat(persistedUser.getPasswordHash()).isEqualTo(PASSWORD_HASH);
        assertThat(persistedUser.getRole()).isEqualTo(role);
        assertThat(persistedRole).isEqualTo(role.name());
    }

    @Test
    void shouldFindExactUserWhenUsernamesDifferOnlyByCase() {
        final var lowerCaseUser = userRepository.saveAndFlush(new User("hotel_guest", PASSWORD_HASH, USER));
        final var mixedCaseUser = userRepository.saveAndFlush(new User("Hotel_Guest", PASSWORD_HASH, USER));
        entityManager.clear();

        final var lowerCaseMatch = userRepository.findByUsername("hotel_guest");
        final var mixedCaseMatch = userRepository.findByUsername("Hotel_Guest");
        final var upperCaseMatch = userRepository.findByUsername("HOTEL_GUEST");

        assertThat(lowerCaseMatch).get()
            .extracting(User::getId)
            .isEqualTo(lowerCaseUser.getId());
        assertThat(mixedCaseMatch).get()
            .extracting(User::getId)
            .isEqualTo(mixedCaseUser.getId());
        assertThat(upperCaseMatch).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenUsernameDoesNotExist() {
        final var user = userRepository.findByUsername("unknown_guest");

        assertThat(user).isEmpty();
    }

    @Test
    void shouldRejectUserWhenUsernameAlreadyExists() {
        userRepository.saveAndFlush(new User("hotel_guest", PASSWORD_HASH, USER));
        final var duplicateUser = new User("hotel_guest", PASSWORD_HASH, USER);

        final var exception = catchThrowable(() -> userRepository.saveAndFlush(duplicateUser));

        assertThat(exception)
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
