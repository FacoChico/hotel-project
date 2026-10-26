package com.example.hotel.booking.service;

import com.example.hotel.booking.BookingServiceApplication;
import com.example.hotel.booking.domain.Booking;
import com.example.hotel.booking.domain.User;
import com.example.hotel.booking.gateway.HotelServiceGateway;
import com.example.hotel.booking.repository.BookingRepository;
import com.example.hotel.booking.repository.UserRepository;
import com.example.hotel.booking.testutil.JwtTestKeys;
import com.example.hotel.booking.testutil.MutableClock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

import static com.example.hotel.booking.domain.BookingReleaseReason.COMPENSATION;
import static com.example.hotel.booking.domain.BookingReleaseReason.USER_CANCELLATION;
import static com.example.hotel.booking.domain.BookingStatus.CANCELLED;
import static com.example.hotel.booking.domain.BookingStatus.CONFIRMED;
import static com.example.hotel.booking.domain.UserRole.USER;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BookingRecoveryRestartTest {
    private static final Instant NOW = Instant.parse("2027-01-10T12:00:00Z");
    @TempDir
    private Path databaseDirectory;

    @Test
    void shouldRecoverPersistedTasksWhenApplicationContextRestarts() {
        // given
        final List<Long> taskIds;
        final long confirmedId;
        try (final var context = startApplication()) {
            final var users = context.getBean(UserRepository.class);
            final var bookings = context.getBean(BookingRepository.class);
            final var transactions = context.getBean(BookingTransactions.class);
            final var user = users.saveAndFlush(new User("hotel_guest", "test-password-hash", USER));
            final var pending = bookings.saveAndFlush(newBooking(user, NOW));
            final var claimed = bookings.saveAndFlush(newBooking(user, NOW));
            final var userCancellation = bookings.saveAndFlush(newBooking(user, NOW));
            transactions.claimRecovery(claimed.getId(), NOW.plusSeconds(3));
            context.getBean(JdbcTemplate.class)
                .update("""
                        UPDATE bookings SET status = 'CANCELLED', release_pending = TRUE,
                            release_reason = 'USER_CANCELLATION' WHERE id = ?
                        """,
                    userCancellation.getId());
            final var confirmed = bookings.saveAndFlush(newBooking(user, NOW.plusSeconds(1)));
            transactions.confirm(confirmed.getId());
            confirmedId = confirmed.getId();
            taskIds = List.of(pending.getId(), claimed.getId(), userCancellation.getId());
        }

        // when
        try (final var context = startApplication()) {
            context.getBean(MutableClock.class).set(NOW.plusSeconds(5));
            context.getBean(BookingRecoveryService.class).recoverBookings();

            // then
            final var bookings = context.getBean(BookingRepository.class);
            for (final var id : taskIds) {
                final var booking = bookings.findById(id).orElseThrow();
                assertThat(booking.getStatus()).isEqualTo(CANCELLED);
                assertThat(booking.isReleasePending()).isFalse();
                assertThat(booking.getNextRecoveryAt()).isNull();
            }
            assertThat(bookings.findById(confirmedId).orElseThrow().getStatus()).isEqualTo(CONFIRMED);
            final var releases = ArgumentCaptor.forClass(Booking.class);
            verify(context.getBean(HotelServiceGateway.class), times(3)).release(releases.capture(), any());
            assertThat(releases.getAllValues()).extracting(Booking::getId).containsExactlyInAnyOrderElementsOf(taskIds);
            assertThat(releases.getAllValues()).extracting(Booking::getReleaseReason)
                .containsExactly(COMPENSATION, USER_CANCELLATION, COMPENSATION);
        }
    }

    private ConfigurableApplicationContext startApplication() {
        final var properties = new LinkedHashMap<String, Object>();
        JwtTestKeys.registerProperties((name, supplier) -> properties.put(name, supplier.get()));
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "0");
        properties.put("eureka.client.enabled", "false");
        properties.put("security.bootstrap-admin.enabled", "false");
        properties.put("booking.recovery.enabled", "false");
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.datasource.url", "jdbc:h2:file:%s;DB_CLOSE_ON_EXIT=FALSE"
            .formatted(databaseDirectory.resolve("bookings")));
        final var arguments = properties.entrySet().stream()
            .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
            .toArray(String[]::new);
        return new SpringApplicationBuilder(BookingServiceApplication.class, RestartConfig.class).run(arguments);
    }

    private static Booking newBooking(User user, Instant deadline) {
        return new Booking(user, randomUUID(), false, null, 42L,
            LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 11), NOW.minusSeconds(10), deadline);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RestartConfig {

        @Bean
        @Primary
        private static MutableClock restartClock() {
            return new MutableClock(NOW);
        }

        @Bean
        @Primary
        private static HotelServiceGateway restartHotelGateway() {
            return mock(HotelServiceGateway.class);
        }
    }
}
