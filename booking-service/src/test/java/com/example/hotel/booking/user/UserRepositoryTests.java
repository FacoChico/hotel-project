package com.example.hotel.booking.user;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import jakarta.persistence.EntityManager;

import static com.example.hotel.booking.user.UserRole.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@DataJpaTest
class UserRepositoryTests {
    private static final String PASSWORD_HASH = "test-password-hash";
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private Flyway flyway;

    @Test
    void shouldHaveUserSchemaWhenMigrationsAreApplied() {
        var migrationInfo = flyway.info();

        var appliedMigrations = migrationInfo.applied();
        var pendingMigrations = migrationInfo.pending();

        assertThat(appliedMigrations)
            .extracting(MigrationInfo::getScript)
            .contains("V1__create_users.sql");
        assertThat(pendingMigrations).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void shouldPersistUser(UserRole role) {
        var user = new User("hotel_guest", PASSWORD_HASH, role);

        var savedUser = userRepository.saveAndFlush(user);
        entityManager.clear();
        var persistedUser = userRepository.findById(savedUser.getId()).orElseThrow();
        var persistedRole = entityManager.createNativeQuery("SELECT role FROM users WHERE id = :id", String.class)
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
        var lowerCaseUser = userRepository.saveAndFlush(new User("hotel_guest", PASSWORD_HASH, USER));
        var mixedCaseUser = userRepository.saveAndFlush(new User("Hotel_Guest", PASSWORD_HASH, USER));
        entityManager.clear();

        var lowerCaseMatch = userRepository.findByUsername("hotel_guest");
        var mixedCaseMatch = userRepository.findByUsername("Hotel_Guest");
        var upperCaseMatch = userRepository.findByUsername("HOTEL_GUEST");

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
        var user = userRepository.findByUsername("unknown_guest");

        assertThat(user).isEmpty();
    }

    @Test
    void shouldRejectUserWhenUsernameAlreadyExists() {
        userRepository.saveAndFlush(new User("hotel_guest", PASSWORD_HASH, USER));
        var duplicateUser = new User("hotel_guest", PASSWORD_HASH, USER);

        var exception = catchThrowable(() -> userRepository.saveAndFlush(duplicateUser));

        assertThat(exception)
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
