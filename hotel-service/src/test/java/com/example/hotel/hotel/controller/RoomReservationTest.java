package com.example.hotel.hotel.controller;

import com.example.hotel.hotel.controller.dto.ConfirmAvailabilityRequest;
import com.example.hotel.hotel.controller.dto.ReleaseReservationRequest;
import com.example.hotel.hotel.domain.Hotel;
import com.example.hotel.hotel.domain.ReservationReleaseReason;
import com.example.hotel.hotel.domain.Room;
import com.example.hotel.hotel.domain.RoomReservation;
import com.example.hotel.hotel.repository.HotelRepository;
import com.example.hotel.hotel.repository.RoomRepository;
import com.example.hotel.hotel.repository.RoomReservationRepository;
import com.example.hotel.hotel.service.RoomReservationService;
import com.example.hotel.hotel.testutil.JwtTestKeys;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import lombok.SneakyThrows;
import tools.jackson.databind.json.JsonMapper;

import static com.example.hotel.hotel.domain.ReservationReleaseReason.COMPENSATION;
import static com.example.hotel.hotel.domain.ReservationReleaseReason.USER_CANCELLATION;
import static com.example.hotel.hotel.domain.RoomReservationState.RELEASED;
import static com.example.hotel.hotel.domain.RoomReservationState.RESERVED;
import static com.example.hotel.hotel.testutil.JwtTestKeys.AUDIENCE;
import static com.example.hotel.hotel.testutil.JwtTestKeys.ISSUER;
import static com.example.hotel.hotel.testutil.JwtTestKeys.KEY_PAIR;
import static com.example.hotel.hotel.testutil.JwtTestKeys.SERVICE_AUDIENCE;
import static com.example.hotel.hotel.testutil.JwtTestKeys.sign;
import static com.nimbusds.jose.JWSAlgorithm.RS256;
import static java.time.ZoneOffset.UTC;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:room_reservation_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc(print = NONE)
@Import(RoomReservationTest.TestClockConfig.class)
class RoomReservationTest {
    private static final Instant NOW = Instant.parse("2027-01-10T12:00:00Z");
    private static final LocalDate START_DATE = LocalDate.of(2027, 1, 11);
    private static final LocalDate END_DATE = LocalDate.of(2027, 1, 13);
    private static final UUID REQUEST_ID = UUID.fromString("c7df6cee-2bd9-43b5-af66-54256c622a34");
    private static final UUID OTHER_REQUEST_ID = UUID.fromString("683d1234-b36a-4a25-a77b-12b789480001");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private RoomReservationRepository reservationRepository;
    @Autowired
    private RoomReservationService reservationService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper mapper;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    private static void registerJwtProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @BeforeEach
    void beforeEach() {
        reservationRepository.deleteAll();
        roomRepository.deleteAll();
        hotelRepository.deleteAll();
    }

    @Test
    @SneakyThrows
    void shouldReserveRoomWhenServiceProvidesAvailableDates() {
        final var room = createRoom(true);

        final var result = confirm(room, 42L);
        final var reservation = findReservation(room, 42L);

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.bookingId").value(42))
            .andExpect(jsonPath("$.roomId").value(room.getId()))
            .andExpect(jsonPath("$.state").value("RESERVED"));
        assertThat(mapper.readTree(result.andReturn().getResponse().getContentAsString()).size()).isEqualTo(3);
        assertThat(result.andReturn().getRequest().getSession(false)).isNull();
        assertThat(result.andReturn().getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(reservation.getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(reservation.getStartDate()).isEqualTo(START_DATE);
        assertThat(reservation.getEndDate()).isEqualTo(END_DATE);
        assertThat(reservation.getState()).isEqualTo(RESERVED);
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
    }

    @Test
    @SneakyThrows
    void shouldPreserveCounterWhenConfirmationIsRepeated() {
        final var room = createRoom(true);
        confirm(room, 42L).andExpect(status().isOk());

        final var result = confirm(room, 42L);

        result.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RESERVED"));
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
    }

    @Test
    @SneakyThrows
    void shouldReturnOriginalReservationWhenRoomHasBecomeUnavailable() {
        final var room = createRoom(true);
        final var request = new ConfirmAvailabilityRequest(42L, REQUEST_ID, START_DATE.minusDays(5), START_DATE.minusDays(3));
        performServicePostOperation(room.getId(), "confirm-availability", request).andExpect(status().isOk());
        performAdminPatch(room, Map.of("available", false)).andExpect(status().isOk());

        final var result = performServicePostOperation(room.getId(), "confirm-availability", request);

        result.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RESERVED"));
        assertThat(findRoom(room).isAvailable()).isFalse();
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
        "2027-01-10, 2027-01-12",
        "2027-01-12, 2027-01-14",
        "2027-01-11, 2027-01-13",
        "2027-01-10, 2027-01-14",
        "2027-01-11, 2027-01-12",
        "2027-01-12, 2027-01-13"
    })
    @SneakyThrows
    void shouldRejectReservationWhenIntervalOverlaps(LocalDate startDate, LocalDate endDate) {
        final var room = createRoom(true);
        confirm(room, 42L).andExpect(status().isOk());
        final var request = new ConfirmAvailabilityRequest(43L, OTHER_REQUEST_ID, startDate, endDate);

        final var result = performServicePostOperation(room.getId(), "confirm-availability", request);

        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_UNAVAILABLE"));
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
        "2027-01-09, 2027-01-11",
        "2027-01-13, 2027-01-15"
    })
    @SneakyThrows
    void shouldReserveRoomWhenIntervalsAreAdjacent(LocalDate startDate, LocalDate endDate) {
        final var room = createRoom(true);
        confirm(room, 42L).andExpect(status().isOk());
        final var request = new ConfirmAvailabilityRequest(43L, OTHER_REQUEST_ID, startDate, endDate);

        final var result = performServicePostOperation(room.getId(), "confirm-availability", request);

        result.andExpect(status().isOk());
        assertThat(reservationRepository.count()).isEqualTo(2);
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(2);
    }

    @Test
    @SneakyThrows
    void shouldReserveAnotherRoomWhenDatesOverlapOnlyInFirstRoom() {
        final var firstRoom = createRoom(true);
        final var secondRoom = roomRepository.saveAndFlush(new Room(firstRoom.getHotel(), "102", true));
        confirm(firstRoom, 42L).andExpect(status().isOk());

        final var result = confirm(secondRoom, 43L);

        result.andExpect(status().isOk());
        assertThat(reservationRepository.count()).isEqualTo(2);
        assertThat(findRoom(firstRoom).getTimesBooked()).isEqualTo(1);
        assertThat(findRoom(secondRoom).getTimesBooked()).isEqualTo(1);
    }

    @Test
    @SneakyThrows
    void shouldRejectReservationWhenRoomIsOperationallyUnavailable() {
        final var room = createRoom(false);

        final var result = confirm(room, 42L);

        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_UNAVAILABLE"));
        assertThat(reservationRepository.count()).isZero();
        assertThat(findRoom(room).getTimesBooked()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm-availability", "release"})
    @SneakyThrows
    void shouldRejectOperationWhenRequestIdDiffers(String operation) {
        final var room = createRoom(true);
        confirm(room, 42L).andExpect(status().isOk());
        final Object request = operation.equals("release")
            ? new ReleaseReservationRequest(42L, OTHER_REQUEST_ID, COMPENSATION)
            : new ConfirmAvailabilityRequest(42L, OTHER_REQUEST_ID, START_DATE, END_DATE);

        final var result = performServicePostOperation(room.getId(), operation, request);

        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(findReservation(room, 42L).getState()).isEqualTo(RESERVED);
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
        "2027-01-10, 2027-01-13",
        "2027-01-11, 2027-01-14"
    })
    @SneakyThrows
    void shouldRejectConfirmationWhenAcceptedDatesDiffer(LocalDate startDate, LocalDate endDate) {
        final var room = createRoom(true);
        confirm(room, 42L).andExpect(status().isOk());

        final var result = performServicePostOperation(room.getId(), "confirm-availability",
            new ConfirmAvailabilityRequest(42L, REQUEST_ID, startDate, endDate));

        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(findReservation(room, 42L).getStartDate()).isEqualTo(START_DATE);
        assertThat(findReservation(room, 42L).getEndDate()).isEqualTo(END_DATE);
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
    }

    @Test
    @SneakyThrows
    void shouldRestoreCounterOnceWhenReservationIsCompensated() {
        final var room = createRoom(true);
        confirm(room, 42L).andExpect(status().isOk());

        final var firstRelease = release(room, COMPENSATION);
        final var repeatedRelease = release(room, COMPENSATION);

        firstRelease.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RELEASED"));
        repeatedRelease.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RELEASED"));
        assertThat(findRoom(room).getTimesBooked()).isZero();
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(findReservation(room, 42L).getStartDate()).isEqualTo(START_DATE);
        assertThat(findReservation(room, 42L).getEndDate()).isEqualTo(END_DATE);
    }

    @Test
    @SneakyThrows
    void shouldPreserveOtherBookingCountsWhenReservationIsCompensated() {
        final var room = createRoom(true);
        confirm(room, 42L).andExpect(status().isOk());
        performServicePostOperation(room.getId(), "confirm-availability",
            new ConfirmAvailabilityRequest(43L, OTHER_REQUEST_ID, END_DATE, END_DATE.plusDays(2)))
            .andExpect(status().isOk());

        final var result = release(room, COMPENSATION);

        result.andExpect(status().isOk());
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
        assertThat(findReservation(room, 42L).getState()).isEqualTo(RELEASED);
        assertThat(findReservation(room, 43L).getState()).isEqualTo(RESERVED);
    }

    @Test
    @SneakyThrows
    void shouldRetainHistoricalCountWhenUserCancellationIsRepeated() {
        final var room = createRoom(true);
        confirm(room, 42L).andExpect(status().isOk());

        release(room, USER_CANCELLATION).andExpect(status().isOk());
        release(room, USER_CANCELLATION).andExpect(status().isOk());
        final var newReservation = confirm(room, 43L);

        newReservation.andExpect(status().isOk());
        assertThat(findReservation(room, 42L).getState()).isEqualTo(RELEASED);
        assertThat(findReservation(room, 43L).getState()).isEqualTo(RESERVED);
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(ReservationReleaseReason.class)
    @SneakyThrows
    void shouldPreserveFirstReleaseEffectWhenRepeatedReasonDiffers(ReservationReleaseReason firstReason) {
        final var room = createRoom(true);
        confirm(room, 42L).andExpect(status().isOk());
        release(room, firstReason).andExpect(status().isOk());
        final var repeatedReason = firstReason == COMPENSATION
            ? USER_CANCELLATION
            : COMPENSATION;

        final var result = release(room, repeatedReason);

        result.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RELEASED"));
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(firstReason == COMPENSATION
            ? 0
            : 1);
    }

    @ParameterizedTest
    @EnumSource(ReservationReleaseReason.class)
    @SneakyThrows
    void shouldPreventLateConfirmationWhenReleaseArrivesFirst(ReservationReleaseReason reason) {
        final var room = createRoom(true);
        release(room, reason).andExpect(status().isOk());
        release(room, reason).andExpect(status().isOk());
        final var result = confirm(room, 42L);

        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_RELEASED"));
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(findReservation(room, 42L).getStartDate()).isNull();
        assertThat(findReservation(room, 42L).getEndDate()).isNull();
        assertThat(findRoom(room).getTimesBooked()).isZero();
    }

    @Test
    @SneakyThrows
    void shouldKeepSlotReleasedWhenLateConfirmationFollowsCompensation() {
        final var room = createRoom(true);
        confirm(room, 42L).andExpect(status().isOk());
        release(room, COMPENSATION).andExpect(status().isOk());

        final var lateConfirmation = confirm(room, 42L);
        final var nextBooking = confirm(room, 43L);

        lateConfirmation.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_RELEASED"));
        nextBooking.andExpect(status().isOk());
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm-availability", "release"})
    @SneakyThrows
    void shouldReturnNotFoundWhenRoomDoesNotExist(String operation) {
        final Object request = operation.equals("release")
            ? new ReleaseReservationRequest(42L, REQUEST_ID, COMPENSATION)
            : new ConfirmAvailabilityRequest(42L, REQUEST_ID, START_DATE, END_DATE);

        final var result = performServicePostOperation(Long.MAX_VALUE, operation, request);

        result.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
        assertThat(reservationRepository.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "2027-01-11, 2027-01-11",
        "2027-01-13, 2027-01-11"
    })
    @SneakyThrows
    void shouldRejectConfirmationWhenDateOrderIsInvalid(LocalDate startDate, LocalDate endDate) {
        final var room = createRoom(true);

        final var result = performServicePostOperation(room.getId(), "confirm-availability",
            new ConfirmAvailabilityRequest(42L, REQUEST_ID, startDate, endDate));

        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(reservationRepository.count()).isZero();
        assertThat(findRoom(room).getTimesBooked()).isZero();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("invalidPayloads")
    @SneakyThrows
    void shouldRejectOperationWhenPayloadViolatesContract(String operation, String payload, String code) {
        final var room = createRoom(true);

        final var result = mockMvc.perform(post("/api/v1/rooms/" + room.getId() + "/" + operation)
            .header(AUTHORIZATION, "Bearer " + serviceToken())
            .contentType(APPLICATION_JSON).content(payload));

        result.andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value(code));
        assertThat(reservationRepository.count()).isZero();
        assertThat(findRoom(room).getTimesBooked()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "confirm-availability, 0, 400",
        "release, 0, 400",
        "confirm-availability, 9223372036854775808, 400",
        "release, 9223372036854775808, 400",
        "confirm-availability, invalid, 404",
        "release, -1, 404"
    })
    @SneakyThrows
    void shouldRejectOperationWhenRoomIdIsInvalid(String operation, String id, int expectedStatus) {
        final var request = operation.equals("release")
            ? mapper.writeValueAsBytes(new ReleaseReservationRequest(42L, REQUEST_ID, COMPENSATION))
            : mapper.writeValueAsBytes(new ConfirmAvailabilityRequest(42L, REQUEST_ID, START_DATE, END_DATE));

        final var result = mockMvc.perform(post("/api/v1/rooms/" + id + "/" + operation)
            .header(AUTHORIZATION, "Bearer " + serviceToken()).contentType(APPLICATION_JSON).content(request));

        result.andExpect(status().is(expectedStatus));
        assertThat(reservationRepository.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "RESERVED, true",
        "RELEASED, true",
        "RELEASED, false"
    })
    @SneakyThrows
    void shouldRejectRoomDeletionWhenReservationHistoryExists(String state, boolean hasDates) {
        final var room = createRoom(true);
        if (hasDates) {
            confirm(room, 42L).andExpect(status().isOk());
        }
        if (state.equals("RELEASED")) {
            release(room, COMPENSATION).andExpect(status().isOk());
        }

        final var result = mockMvc.perform(delete("/api/v1/rooms/" + room.getId())
            .header(AUTHORIZATION, "Bearer " + adminToken()));

        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_HAS_RESERVATIONS"));
        assertThat(roomRepository.findById(room.getId())).isPresent();
        assertThat(reservationRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
        "2027-01-09, 2027-01-12",
        "2027-01-10, 2027-01-12",
        "2027-01-11, 2027-01-13"
    })
    @SneakyThrows
    void shouldRejectDisablingWhenRoomHasCurrentOrFutureReservations(LocalDate startDate, LocalDate endDate) {
        final var room = createRoom(true);
        performServicePostOperation(room.getId(), "confirm-availability",
            new ConfirmAvailabilityRequest(42L, REQUEST_ID, startDate, endDate)).andExpect(status().isOk());

        final var result = performAdminPatch(room, Map.of("number", "102", "available", false));

        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_HAS_FUTURE_RESERVATIONS"));
        assertThat(findRoom(room).getNumber()).isEqualTo("101");
        assertThat(findRoom(room).isAvailable()).isTrue();
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
        "2027-01-08, 2027-01-10, false",
        "2027-01-11, 2027-01-13, true"
    })
    @SneakyThrows
    void shouldAllowDisablingWhenReservationsHaveEndedOrBeenReleased(LocalDate startDate,
                                                                     LocalDate endDate, boolean released) {
        final var room = createRoom(true);
        performServicePostOperation(room.getId(), "confirm-availability",
            new ConfirmAvailabilityRequest(42L, REQUEST_ID, startDate, endDate)).andExpect(status().isOk());
        if (released) {
            release(room, COMPENSATION).andExpect(status().isOk());
        }

        final var result = performAdminPatch(room, Map.of("available", false));

        result.andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
        assertThat(findRoom(room).isAvailable()).isFalse();
    }

    @Test
    @SneakyThrows
    void shouldAllowNumberChangeWhenRoomHasFutureReservations() {
        final var room = createRoom(true);
        confirm(room, 42L).andExpect(status().isOk());

        final var result = performAdminPatch(room, Map.of("number", "102"));

        result.andExpect(status().isOk()).andExpect(jsonPath("$.number").value("102"));
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
    }

    @Test
    @SneakyThrows
    void shouldReserveOnceWhenConfirmationIsDeliveredConcurrently() {
        final var room = createRoom(true);

        final var statuses = runConcurrently(() -> confirm(room, 42L).andReturn().getResponse().getStatus(),
            () -> confirm(room, 42L).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactly(200, 200);
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
    }

    @Test
    @SneakyThrows
    void shouldReserveOneBookingWhenOverlappingRequestsRace() {
        final var room = createRoom(true);

        final var statuses = runConcurrently(() -> confirm(room, 42L).andReturn().getResponse().getStatus(),
            () -> confirm(room, 43L).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(ReservationReleaseReason.class)
    @SneakyThrows
    void shouldReleaseOnceWhenReleaseIsDeliveredConcurrently(ReservationReleaseReason reason) {
        final var room = createRoom(true);
        confirm(room, 42L).andExpect(status().isOk());

        final var statuses = runConcurrently(() -> release(room, reason).andReturn().getResponse().getStatus(),
            () -> release(room, reason).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactly(200, 200);
        assertThat(findReservation(room, 42L).getState()).isEqualTo(RELEASED);
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(reason == COMPENSATION
            ? 0
            : 1);
    }

    @Test
    @SneakyThrows
    void shouldKeepReservationReleasedWhenConfirmationAndCompensationRace() {
        final var room = createRoom(true);

        final var statuses = runConcurrently(() -> confirm(room, 42L).andReturn().getResponse().getStatus(),
            () -> release(room, COMPENSATION).andReturn().getResponse().getStatus());

        assertThat(statuses.get(0)).isIn(200, 409);
        assertThat(statuses.get(1)).isEqualTo(200);
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(findReservation(room, 42L).getState()).isEqualTo(RELEASED);
        assertThat(findRoom(room).getTimesBooked()).isZero();
        confirm(room, 42L).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_RELEASED"));
    }

    @Test
    @SneakyThrows
    void shouldPreserveReservationInvariantWhenConfirmationAndDisablingRace() {
        final var room = createRoom(true);

        final var statuses = runConcurrently(() -> confirm(room, 42L).andReturn().getResponse().getStatus(),
            () -> performAdminPatch(room, Map.of("available", false)).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        final var confirmed = statuses.get(0) == 200;
        assertThat(findRoom(room).isAvailable()).isEqualTo(confirmed);
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(confirmed
            ? 1
            : 0);
        assertThat(reservationRepository.count()).isEqualTo(confirmed
            ? 1
            : 0);
    }

    @Test
    @SneakyThrows
    void shouldPreserveHistoryWhenConfirmationAndRoomDeletionRace() {
        final var room = createRoom(true);

        final var statuses = runConcurrently(() -> confirm(room, 42L).andReturn().getResponse().getStatus(),
            () -> mockMvc.perform(delete("/api/v1/rooms/" + room.getId())
                .header(AUTHORIZATION, "Bearer " + adminToken())).andReturn().getResponse().getStatus());

        assertThat(statuses).isIn(List.of(200, 409), List.of(404, 204));
        assertThat(reservationRepository.count()).isEqualTo(statuses.get(0) == 200
            ? 1
            : 0);
        assertThat(roomRepository.existsById(room.getId())).isEqualTo(statuses.get(0) == 200);
    }

    @Test
    @SneakyThrows
    void shouldPreserveCounterWhenConfirmationAndNumberChangeRace() {
        final var room = createRoom(true);

        final var statuses = runConcurrently(() -> confirm(room, 42L).andReturn().getResponse().getStatus(),
            () -> performAdminPatch(room, Map.of("number", "102")).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactly(200, 200);
        assertThat(findRoom(room).getNumber()).isEqualTo("102");
        assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
    }

    @Test
    @SneakyThrows
    void shouldReturnServiceUnavailableWhenRoomLockCannotBeAcquired() {
        // given
        final var room = createRoom(true);
        final var locked = new CountDownLatch(1);
        final var unlock = new CountDownLatch(1);
        final var transaction = new TransactionTemplate(transactionManager);

        try (final var executor = Executors.newSingleThreadExecutor()) {
            final var holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                roomRepository.findByIdForUpdate(room.getId()).orElseThrow();
                locked.countDown();
                await(unlock);
            }));
            try {
                assertThat(locked.await(5, SECONDS)).isTrue();

                // when
                final var result = confirm(room, 42L);

                // then
                result.andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("ROOM_BUSY"))
                    .andExpect(jsonPath("$.detail").value("Room is temporarily busy"));
                assertThat(reservationRepository.count()).isZero();
                assertThat(findRoom(room).getTimesBooked()).isZero();
            } finally {
                unlock.countDown();
            }
            holder.get(10, SECONDS);
        }
    }

    @Test
    void shouldRollBackReservationAndCounterWhenConfirmationFlushFails() {
        final var room = createRoom(true);
        jdbcTemplate.execute("ALTER TABLE rooms ADD CONSTRAINT test_counter_write_failure CHECK (times_booked = 0)");
        try {
            final var exception = catchThrowable(() -> reservationService.confirmAvailability(room.getId(),
                new ConfirmAvailabilityRequest(42L, REQUEST_ID, START_DATE, END_DATE)));

            assertThat(exception).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(reservationRepository.count()).isZero();
            assertThat(findRoom(room).getTimesBooked()).isZero();
        } finally {
            jdbcTemplate.execute("ALTER TABLE rooms DROP CONSTRAINT test_counter_write_failure");
        }
    }

    @Test
    @SneakyThrows
    void shouldRollBackReleaseAndCounterWhenReleaseFlushFails() {
        final var room = createRoom(true);
        confirm(room, 42L).andExpect(status().isOk());
        jdbcTemplate.execute("ALTER TABLE rooms ADD CONSTRAINT test_counter_write_failure CHECK (times_booked = 1)");
        try {
            final var exception = catchThrowable(() -> reservationService.releaseReservation(room.getId(),
                new ReleaseReservationRequest(42L, REQUEST_ID, COMPENSATION)));

            assertThat(exception).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(findReservation(room, 42L).getState()).isEqualTo(RESERVED);
            assertThat(findRoom(room).getTimesBooked()).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("ALTER TABLE rooms DROP CONSTRAINT test_counter_write_failure");
        }
    }

    private Room createRoom(boolean available) {
        final var hotel = hotelRepository.saveAndFlush(new Hotel("City Hotel", "Central Street, 15"));
        return roomRepository.saveAndFlush(new Room(hotel, "101", available));
    }

    private ResultActions confirm(Room room, long bookingId) {
        return performServicePostOperation(room.getId(), "confirm-availability",
            new ConfirmAvailabilityRequest(bookingId, REQUEST_ID, START_DATE, END_DATE));
    }

    private RoomReservation findReservation(Room room, long bookingId) {
        return reservationRepository.findByBookingIdAndRoomId(bookingId, room.getId()).orElseThrow();
    }

    private Room findRoom(Room room) {
        return roomRepository.findById(room.getId()).orElseThrow();
    }

    private ResultActions release(Room room, ReservationReleaseReason reason) {
        return performServicePostOperation(room.getId(), "release", new ReleaseReservationRequest(42L, REQUEST_ID, reason));
    }

    @SneakyThrows
    private ResultActions performServicePostOperation(long roomId, String operation, Object payload) {
        return mockMvc.perform(post("/api/v1/rooms/" + roomId + "/" + operation)
            .header(AUTHORIZATION, "Bearer " + serviceToken())
            .contentType(APPLICATION_JSON).content(mapper.writeValueAsBytes(payload)));
    }

    @SneakyThrows
    private ResultActions performAdminPatch(Room room, Object payload) {
        return mockMvc.perform(patch("/api/v1/rooms/" + room.getId())
            .header(AUTHORIZATION, "Bearer " + adminToken())
            .contentType(APPLICATION_JSON).content(mapper.writeValueAsBytes(payload)));
    }

    private static String serviceToken() {
        return sign(Map.of("sub", "booking-service", "iss", ISSUER, "aud", List.of(SERVICE_AUDIENCE),
            "iat", Date.from(NOW), "exp", Date.from(NOW.plusSeconds(120)), "scope", "reservations:write"), KEY_PAIR, RS256);
    }

    private static String adminToken() {
        return sign(Map.of("sub", "42", "iss", ISSUER, "aud", List.of(AUDIENCE), "roles", List.of("ADMIN"),
            "iat", Date.from(NOW), "exp", Date.from(NOW.plusSeconds(3600))), KEY_PAIR, RS256);
    }

    @SneakyThrows
    private static List<Integer> runConcurrently(Callable<Integer> first, Callable<Integer> second) {
        final var ready = new CountDownLatch(2);
        final var start = new CountDownLatch(1);
        try (final var executor = Executors.newFixedThreadPool(2)) {
            final var firstResult = executor.submit(() -> {
                ready.countDown();
                await(start);
                return first.call();
            });
            final var secondResult = executor.submit(() -> {
                ready.countDown();
                await(start);
                return second.call();
            });
            try {
                assertThat(ready.await(5, SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            return List.of(firstResult.get(10, SECONDS), secondResult.get(10, SECONDS));
        }
    }

    @SneakyThrows
    private static void await(CountDownLatch latch) {
        if (!latch.await(10, SECONDS)) {
            throw new IllegalStateException("Concurrent operation did not reach its barrier");
        }
    }

    private static Stream<Arguments> invalidPayloads() {
        final var confirmTemplate = """
            {"bookingId":42,"requestId":"c7df6cee-2bd9-43b5-af66-54256c622a34",
             "startDate":"2027-01-11","endDate":"2027-01-13"}
            """;
        final var releaseTemplate = """
            {"bookingId":42,"requestId":"c7df6cee-2bd9-43b5-af66-54256c622a34","reason":"COMPENSATION"}
            """;
        return Stream.of(
            arguments("confirm-availability", "{}", "VALIDATION_FAILED"),
            arguments(
                "confirm-availability",
                confirmTemplate.replace("\"bookingId\":42", "\"bookingId\":null"),
                "VALIDATION_FAILED"
            ),
            arguments(
                "confirm-availability",
                confirmTemplate.replace("\"bookingId\":42", "\"bookingId\":0"),
                "VALIDATION_FAILED"
            ),
            arguments(
                "confirm-availability",
                confirmTemplate.replace("\"bookingId\":42", "\"bookingId\":-1"),
                "VALIDATION_FAILED"
            ),
            arguments(
                "confirm-availability",
                confirmTemplate.replace("\"bookingId\":42", "\"bookingId\":1.5"),
                "INVALID_REQUEST_BODY"
            ),
            arguments(
                "confirm-availability",
                confirmTemplate.replace("\"bookingId\":42", "\"bookingId\":\"42\""),
                "INVALID_REQUEST_BODY"
            ),
            arguments(
                "confirm-availability",
                confirmTemplate.replace("\"bookingId\":42", "\"bookingId\":true"),
                "INVALID_REQUEST_BODY"
            ),
            arguments(
                "confirm-availability",
                confirmTemplate.replace("\"bookingId\":42", "\"bookingId\":9223372036854775808"),
                "INVALID_REQUEST_BODY"
            ),
            arguments("confirm-availability", confirmTemplate.replace("\"2027-01-11\"", "null"), "VALIDATION_FAILED"),
            arguments(
                "confirm-availability",
                confirmTemplate.replace("2027-01-13", "2027-02-30"),
                "INVALID_REQUEST_BODY"
            ),
            arguments(
                "confirm-availability",
                confirmTemplate.replace("\"2027-01-11\"", "[2027,1,11]"),
                "INVALID_REQUEST_BODY"
            ),
            arguments(
                "confirm-availability",
                confirmTemplate.replace("2027-01-11", "2027-01-11T00:00:00Z"),
                "INVALID_REQUEST_BODY"
            ),
            arguments(
                "confirm-availability",
                confirmTemplate.replace(REQUEST_ID.toString(), "invalid"),
                "INVALID_REQUEST_BODY"
            ),
            arguments(
                "confirm-availability",
                confirmTemplate.replace("\"" + REQUEST_ID + "\"", "null"),
                "VALIDATION_FAILED"
            ),
            arguments(
                "confirm-availability",
                confirmTemplate.replace("\"bookingId\":42", "\"state\":\"RESERVED\",\"bookingId\":42"),
                "INVALID_REQUEST_BODY"
            ),
            arguments("release", "{}", "VALIDATION_FAILED"),
            arguments("release", releaseTemplate.replace("\"COMPENSATION\"", "null"), "VALIDATION_FAILED"),
            arguments("release", releaseTemplate.replace("\"COMPENSATION\"", "0"), "INVALID_REQUEST_BODY"),
            arguments("release", releaseTemplate.replace("COMPENSATION", "UNKNOWN"), "INVALID_REQUEST_BODY"),
            arguments(
                "release",
                releaseTemplate.replace("\"bookingId\":42", "\"bookingId\":\"42\""),
                "INVALID_REQUEST_BODY"
            ),
            arguments("release", releaseTemplate.replace(REQUEST_ID.toString(), "invalid"), "INVALID_REQUEST_BODY"),
            arguments("release", releaseTemplate.replace("\"bookingId\":42", "\"startDate\":\"2027-01-11\",\"bookingId\":42"), "INVALID_REQUEST_BODY")
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {

        @Bean
        @Primary
        private static Clock testClock() {
            return Clock.fixed(NOW, UTC);
        }
    }
}
