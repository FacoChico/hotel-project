package com.example.hotel.booking.controller;

import com.example.hotel.booking.domain.Booking;
import com.example.hotel.booking.domain.BookingStatus;
import com.example.hotel.booking.domain.User;
import com.example.hotel.booking.repository.BookingRepository;
import com.example.hotel.booking.repository.UserRepository;
import com.example.hotel.booking.security.JwtTokenService;
import com.example.hotel.booking.testutil.JwtTestKeys;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;

import lombok.SneakyThrows;

import static com.example.hotel.booking.domain.UserRole.ADMIN;
import static com.example.hotel.booking.domain.UserRole.USER;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "security.bootstrap-admin.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:user_booking_history_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc(print = NONE)
class UserBookingHistoryTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @MockitoSpyBean
    private BookingRepository bookingRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtTokenService tokenService;

    @DynamicPropertySource
    private static void registerJwtProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @BeforeEach
    void clearHistory() {
        bookingRepository.deleteAll();
        userRepository.deleteAll();
    }

    @ParameterizedTest
    @EnumSource(BookingStatus.class)
    @SneakyThrows
    void shouldRejectDeletionWhenUserHasBookingHistory(BookingStatus bookingStatus) {
        final var user = createUser("hotel_guest");
        final var booking = createBooking(user);
        jdbcTemplate.update("UPDATE bookings SET status = ? WHERE id = ?", bookingStatus.name(), booking.getId());
        final var token = tokenService.issueToken(42, ADMIN);

        final var result = mockMvc.perform(delete("/api/v1/users/{id}", user.getId())
            .header(AUTHORIZATION, "Bearer " + token));

        result.andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("USER_HAS_BOOKINGS"))
            .andExpect(jsonPath("$.detail").value("User has booking history"));
        assertThat(userRepository.findById(user.getId())).isPresent();
        assertThat(bookingRepository.findById(booking.getId())).get()
            .extracting(Booking::getStatus).isEqualTo(bookingStatus);
    }

    @Test
    @SneakyThrows
    void shouldRejectDeletionWhenHistoryCheckReturnsStaleResult() {
        final var user = createUser("hotel_guest");
        final var booking = createBooking(user);
        doReturn(false).when(bookingRepository).existsByUserId(user.getId());
        final var token = tokenService.issueToken(42, ADMIN);

        final var result = mockMvc.perform(delete("/api/v1/users/{id}", user.getId())
            .header(AUTHORIZATION, "Bearer " + token));

        result.andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("USER_HAS_BOOKINGS"))
            .andExpect(jsonPath("$.detail").value("User has booking history"));
        assertThat(userRepository.findById(user.getId())).isPresent();
        assertThat(bookingRepository.findById(booking.getId())).isPresent();
    }

    @Test
    @SneakyThrows
    void shouldDeleteUserWhenOnlyAnotherUserHasBookings() {
        final var owner = createUser("booking_owner");
        final var booking = createBooking(owner);
        final var user = createUser("another_guest");
        final var token = tokenService.issueToken(42, ADMIN);

        final var result = mockMvc.perform(delete("/api/v1/users/{id}", user.getId())
            .header(AUTHORIZATION, "Bearer " + token));

        result.andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(userRepository.findById(user.getId())).isEmpty();
        assertThat(userRepository.findById(owner.getId())).isPresent();
        assertThat(bookingRepository.findById(booking.getId())).isPresent();
    }

    private User createUser(String username) {
        return userRepository.saveAndFlush(new User(username, "test-password-hash", USER));
    }

    private Booking createBooking(User user) {
        final var createdAt = Instant.parse("2027-01-01T10:00:00Z");
        return bookingRepository.saveAndFlush(new Booking(user, randomUUID(), false, null, 42L,
            LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 12), createdAt, createdAt.plusSeconds(30)));
    }
}
