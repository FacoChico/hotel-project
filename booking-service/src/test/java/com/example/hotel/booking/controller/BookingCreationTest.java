package com.example.hotel.booking.controller;

import com.example.hotel.booking.domain.Booking;
import com.example.hotel.booking.domain.User;
import com.example.hotel.booking.gateway.HotelServiceGateway;
import com.example.hotel.booking.gateway.exception.HotelServiceException;
import com.example.hotel.booking.properties.HotelClientProperties;
import com.example.hotel.booking.repository.BookingRepository;
import com.example.hotel.booking.repository.UserRepository;
import com.example.hotel.booking.security.InternalTokenService;
import com.example.hotel.booking.security.JwtTokenService;
import com.example.hotel.booking.service.BookingTransactions;
import com.example.hotel.booking.testutil.JwtTestKeys;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import lombok.SneakyThrows;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.example.hotel.booking.domain.BookingReleaseReason.COMPENSATION;
import static com.example.hotel.booking.domain.BookingStatus.CANCELLED;
import static com.example.hotel.booking.domain.BookingStatus.CONFIRMED;
import static com.example.hotel.booking.domain.BookingStatus.PENDING;
import static com.example.hotel.booking.domain.UserRole.ADMIN;
import static com.example.hotel.booking.domain.UserRole.USER;
import static java.lang.System.nanoTime;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZoneOffset.UTC;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.LOCATION;
import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive;

@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "security.bootstrap-admin.enabled=false",
    "booking.recovery.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:booking_creation_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "booking.hotel-client.read-timeout-ms=500",
    "booking.hotel-client.backoff-ms=5",
    "booking.hotel-client.operation-timeout-ms=5000"
})
@AutoConfigureMockMvc(print = NONE)
@Import(BookingCreationTest.ClockConfig.class)
class BookingCreationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicReference<JWSVerifier> JWT_VERIFIER = new AtomicReference<>();
    private static final AtomicReference<Duration> CLOCK_OFFSET = new AtomicReference<>(Duration.ZERO);
    private static final List<HotelRequest> HOTEL_REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<Consumer<HotelRequest>> HOTEL_HANDLER = new AtomicReference<>();
    private static final ExecutorService HOTEL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer HOTEL_SERVER = startHotelServer();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private UserRepository userRepository;
    @MockitoSpyBean
    private BookingTransactions transactions;
    @Autowired
    private JwtTokenService tokenService;
    @Autowired
    private InternalTokenService internalTokenService;
    @Autowired
    private HotelServiceGateway hotelServiceGateway;
    @Autowired
    @LoadBalanced
    private RestClient.Builder hotelRestClientBuilder;
    @Autowired
    private HttpClient hotelHttpClient;
    @Autowired
    private HotelClientProperties properties;
    @Autowired
    private Clock clock;
    @Autowired
    private KeyPair jwtKeyPair;
    private User user;

    @DynamicPropertySource
    private static void registerProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
        registry.add("spring.cloud.discovery.client.simple.instances.hotel-service[0].uri",
            () -> "http://127.0.0.1:" + HOTEL_SERVER.getAddress().getPort());
    }

    @BeforeEach
    void prepareBooking() {
        JWT_VERIFIER.set(new RSASSAVerifier((RSAPublicKey) jwtKeyPair.getPublic()));
        CLOCK_OFFSET.set(Duration.ZERO);
        bookingRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.saveAndFlush(new User("hotel_guest", "test-password-hash", USER));
        HOTEL_REQUESTS.clear();
        HOTEL_HANDLER.set(BookingCreationTest::respondSuccessfully);
    }

    @AfterAll
    static void stopHotelServer() {
        HOTEL_SERVER.stop(0);
        HOTEL_EXECUTOR.close();
    }

    @Test
    @SneakyThrows
    void shouldConfirmBookingWhenHotelAcceptsReservation() {
        final var payload = validRequest();
        final var visiblePending = new AtomicReference<Booking>();
        HOTEL_HANDLER.set(request -> {
            visiblePending.set(bookingRepository.findById(request.body().get("bookingId").longValue()).orElseThrow());
            respondSuccessfully(request);
        });

        final var result = create(payload);
        final var response = JSON.readTree(result.getResponse().getContentAsString());
        final var booking = bookingRepository.findById(response.get("id").longValue()).orElseThrow();
        final var request = requests("confirm-availability").getFirst();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getHeader(LOCATION)).isEqualTo("/api/v1/bookings/" + booking.getId());
        assertThat(response.size()).isEqualTo(8);
        assertThat(response.get("requestId").asString()).isEqualTo(payload.get("requestId").toString());
        assertThat(response.get("roomId").longValue()).isEqualTo(42);
        assertThat(response.get("startDate").asString()).isEqualTo(payload.get("startDate"));
        assertThat(response.get("endDate").asString()).isEqualTo(payload.get("endDate"));
        assertThat(response.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(response.get("releasePending").booleanValue()).isFalse();
        assertThat(booking.getStatus()).isEqualTo(CONFIRMED);
        assertThat(booking.getUser().getId()).isEqualTo(user.getId());
        assertThat(visiblePending.get().getStatus()).isEqualTo(PENDING);
        assertThat(request.body().size()).isEqualTo(4);
        assertThat(request.body().get("requestId").asString()).isEqualTo(payload.get("requestId").toString());
        assertThat(request.body().get("startDate").asString()).isEqualTo(payload.get("startDate"));
        assertThat(request.claims().getSubject()).isEqualTo("booking-service");
        assertThat(request.claims().getAudience()).containsExactly("hotel-internal");
        assertThat(request.claims().getStringClaim("scope")).isEqualTo("reservations:write");
        assertThat(request.signatureVerified()).isTrue();
        assertThat(HOTEL_REQUESTS).hasSize(1);
    }

    @Test
    @SneakyThrows
    void shouldReturnStoredBookingWhenCompletedRequestIsRepeated() {
        final var payload = validRequest();
        final var first = create(payload);

        final var repeated = create(payload);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(repeated.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(repeated.getResponse().getContentAsString()))
            .isEqualTo(JSON.readTree(first.getResponse().getContentAsString()));
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(HOTEL_REQUESTS).hasSize(1);
    }

    @Test
    @SneakyThrows
    void shouldResolveAcceptedRequestWhenStayHasBecomePast() {
        final var payload = validRequest();
        final var first = create(payload);
        CLOCK_OFFSET.set(Duration.ofDays(3));

        final var repeated = create(payload);

        assertThat(repeated.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(repeated.getResponse().getContentAsString()))
            .isEqualTo(JSON.readTree(first.getResponse().getContentAsString()));
        assertThat(HOTEL_REQUESTS).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"roomId", "startDate", "endDate", "autoSelect", "hotelId"})
    @SneakyThrows
    void shouldRejectRepeatWhenMeaningfulParametersDiffer(String field) {
        final var payload = validRequest();
        create(payload);
        final var changed = new LinkedHashMap<>(payload);
        changed.put(field, switch (field) {
            case "roomId", "hotelId" -> 43;
            case "autoSelect" -> true;
            default -> LocalDate.now(clock).plusDays(10).toString();
        });

        final var result = create(changed);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asString())
            .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(bookingRepository.findAll().getFirst().getStatus()).isEqualTo(CONFIRMED);
        assertThat(HOTEL_REQUESTS).hasSize(1);
    }

    @Test
    @SneakyThrows
    void shouldCreateIndependentBookingsWhenUsersShareRequestId() {
        final var payload = validRequest();
        final var first = create(payload);
        final var anotherUser = userRepository.saveAndFlush(new User("another_guest", "test-password-hash", USER));

        final var second = create(payload, tokenService.issueToken(anotherUser.getId(), USER));

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(bookingRepository.count()).isEqualTo(2);
        assertThat(requests("confirm-availability")).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    @SneakyThrows
    void shouldConfirmOnceWhenTransientFailuresPrecedeSuccess(int failureStatus) {
        final var calls = new AtomicInteger();
        HOTEL_HANDLER.set(request -> {
            if (calls.incrementAndGet() < 3) {
                respond(request, failureStatus, Map.of("detail", "private upstream diagnostic"));
            } else {
                respondSuccessfully(request);
            }
        });

        final var result = create(validRequest());

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(bookingRepository.findAll().getFirst().getStatus()).isEqualTo(CONFIRMED);
        assertThat(requests("confirm-availability")).hasSize(3);
        assertThat(requests("confirm-availability").stream()
            .map(request -> request.body().get("bookingId").longValue()).distinct().count()).isEqualTo(1);
        assertThat(requests("confirm-availability").stream()
            .map(request -> request.body().get("requestId").asString()).distinct().count()).isEqualTo(1);
        assertThat(requests("release")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"400,503", "401,503", "403,503", "404,404", "409,409", "500,503"})
    @SneakyThrows
    void shouldCancelWithoutRetryWhenHotelReturnsTerminalFailure(int hotelStatus, int expectedStatus) {
        HOTEL_HANDLER.set(request -> {
            if (request.path().endsWith("confirm-availability")) {
                respond(request, hotelStatus, Map.of("code", hotelStatus == 404
                        ? "ROOM_NOT_FOUND"
                        : "ROOM_UNAVAILABLE",
                    "detail", "private upstream diagnostic"));
            } else {
                respondSuccessfully(request);
            }
        });

        final var result = create(validRequest());
        final var booking = bookingRepository.findAll().getFirst();

        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertThat(result.getResponse().getContentType()).startsWith(APPLICATION_PROBLEM_JSON.toString());
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private upstream diagnostic");
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("bookingId").longValue())
            .isEqualTo(booking.getId());
        assertThat(booking.getStatus()).isEqualTo(CANCELLED);
        assertThat(booking.isReleasePending()).isFalse();
        assertThat(booking.getReleaseReason()).isEqualTo(COMPENSATION);
        assertThat(requests("confirm-availability")).hasSize(1);
        assertThat(requests("release")).hasSize(1);
        assertThat(requests("release").getFirst().body().get("reason").asString()).isEqualTo("COMPENSATION");
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "null", "wrong booking", "wrong room", "wrong state", "malformed"})
    @SneakyThrows
    void shouldCompensateWhenHotelSuccessResponseIsInvalid(String scenario) {
        HOTEL_HANDLER.set(request -> {
            if (request.path().endsWith("release")) {
                respondSuccessfully(request);
                return;
            }
            final var reply = new LinkedHashMap<String, Object>();
            reply.put("bookingId", request.body().get("bookingId").longValue());
            reply.put("roomId", 42);
            reply.put("state", "RESERVED");
            switch (scenario) {
                case "wrong booking" -> reply.put("bookingId", Long.MAX_VALUE);
                case "wrong room" -> reply.put("roomId", 43);
                case "wrong state" -> reply.put("state", "RELEASED");
                case "empty" -> reply.clear();
                default -> {
                }
            }
            respond(request, 200, switch (scenario) {
                case "null" -> "null";
                case "malformed" -> "{invalid";
                default -> reply;
            });
        });

        final var result = create(validRequest());

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(bookingRepository.findAll().getFirst().getStatus()).isEqualTo(CANCELLED);
        assertThat(bookingRepository.findAll().getFirst().isReleasePending()).isFalse();
        assertThat(requests("confirm-availability")).hasSize(1);
        assertThat(requests("release")).hasSize(1);
    }

    @Test
    @SneakyThrows
    void shouldRetainReleaseTaskWhenCompensationFails() {
        final var payload = validRequest();
        HOTEL_HANDLER.set(request -> respond(request, 503, Map.of()));

        final var first = create(payload);
        final var repeated = create(payload);
        final var booking = bookingRepository.findAll().getFirst();

        assertThat(first.getResponse().getStatus()).isEqualTo(503);
        assertThat(repeated.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(first.getResponse().getContentAsString()).get("releasePending").booleanValue()).isTrue();
        assertThat(JSON.readTree(repeated.getResponse().getContentAsString()).get("releasePending").booleanValue()).isTrue();
        assertThat(booking.getStatus()).isEqualTo(CANCELLED);
        assertThat(booking.getReleaseReason()).isEqualTo(COMPENSATION);
        assertThat(booking.isReleasePending()).isTrue();
        assertThat(requests("confirm-availability")).hasSize(3);
        assertThat(requests("release")).hasSize(3);
    }

    @Test
    @SneakyThrows
    void shouldCompleteCompensationWhenRoomDoesNotExist() {
        HOTEL_HANDLER.set(request -> respond(request, 404, Map.of("code", "ROOM_NOT_FOUND")));

        final var result = create(validRequest());
        final var booking = bookingRepository.findAll().getFirst();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(booking.getStatus()).isEqualTo(CANCELLED);
        assertThat(booking.isReleasePending()).isFalse();
        assertThat(requests("confirm-availability")).hasSize(1);
        assertThat(requests("release")).hasSize(1);
    }

    @Test
    @SneakyThrows
    void shouldFinishCompensationWhenTransientReleaseFailuresRecover() {
        final var releaseCalls = new AtomicInteger();
        HOTEL_HANDLER.set(request -> {
            if (request.path().endsWith("confirm-availability") || releaseCalls.incrementAndGet() < 3) {
                respond(request, 503, Map.of());
            } else {
                respondSuccessfully(request);
            }
        });

        final var result = create(validRequest());

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(bookingRepository.findAll().getFirst().isReleasePending()).isFalse();
        assertThat(requests("release")).hasSize(3);
    }

    @Test
    @SneakyThrows
    void shouldReleaseReservationWhenConfirmationResponsesAreLost() {
        // given
        final var allowResponse = new CountDownLatch(1);
        HOTEL_HANDLER.set(request -> {
            if (request.path().endsWith("confirm-availability")) {
                await(allowResponse);
            }
            respondSuccessfully(request);
        });
        try {
            // when
            final var result = create(validRequest());

            // then
            assertThat(result.getResponse().getStatus()).isEqualTo(504);
            assertThat(bookingRepository.findAll().getFirst().getStatus()).isEqualTo(CANCELLED);
            assertThat(bookingRepository.findAll().getFirst().isReleasePending()).isFalse();
            assertThat(requests("confirm-availability")).hasSize(3);
            assertThat(requests("release")).hasSize(1);
        } finally {
            allowResponse.countDown();
        }
    }

    @Test
    @SneakyThrows
    void shouldReturnPendingWhenDuplicateArrivesDuringConfirmation() {
        // given
        final var enteredHotel = new CountDownLatch(1);
        final var allowResponse = new CountDownLatch(1);
        final var payload = validRequest();
        HOTEL_HANDLER.set(request -> {
            enteredHotel.countDown();
            await(allowResponse);
            respondSuccessfully(request);
        });
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // when
            final var first = executor.submit(() -> create(payload));
            assertThat(enteredHotel.await(3, SECONDS)).isTrue();
            final var duplicate = create(payload);
            allowResponse.countDown();
            final var original = first.get(3, SECONDS);

            // then
            assertThat(duplicate.getResponse().getStatus()).isEqualTo(202);
            assertThat(JSON.readTree(duplicate.getResponse().getContentAsString()).get("status").asString())
                .isEqualTo("PENDING");
            assertThat(duplicate.getResponse().getHeader(LOCATION)).isNotBlank();
            assertThat(original.getResponse().getStatus()).isEqualTo(201);
            assertThat(bookingRepository.count()).isEqualTo(1);
            assertThat(requests("confirm-availability")).hasSize(1);
        } finally {
            allowResponse.countDown();
        }
    }

    @Test
    @SneakyThrows
    void shouldKeepCancellationWhenCancellationWinsConfirmationRace() {
        HOTEL_HANDLER.set(request -> {
            if (request.path().endsWith("confirm-availability")) {
                final var bookingId = request.body().get("bookingId").longValue();
                assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus()).isEqualTo(PENDING);
                assertThat(cancel(bookingId).getResponse().getStatus()).isEqualTo(200);
            }
            respondSuccessfully(request);
        });

        final var result = create(validRequest());

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asString())
            .isEqualTo("BOOKING_CANCELLED");
        assertThat(bookingRepository.findAll().getFirst().getStatus()).isEqualTo(CANCELLED);
        assertThat(bookingRepository.findAll().getFirst().isReleasePending()).isFalse();
        assertThat(requests("release")).hasSize(1);
        assertThat(requests("release").getFirst().body().get("reason").asString()).isEqualTo("COMPENSATION");
    }

    @Test
    @SneakyThrows
    void shouldUseUserCancellationWhenConfirmationWinsRowLockRace() {
        // given
        final var confirmationReady = new CountDownLatch(1);
        final var allowConfirmationCommit = new CountDownLatch(1);
        final var cancellationStarted = new CountDownLatch(1);
        final var bookingId = new AtomicReference<Long>();
        doAnswer(invocation -> {
            final var confirmed = (Booking) invocation.callRealMethod();
            assertThat(isActualTransactionActive()).isTrue();
            bookingId.set(confirmed.getId());
            confirmationReady.countDown();
            await(allowConfirmationCommit);
            return confirmed;
        }).when(transactions).confirm(anyLong());
        doAnswer(invocation -> {
            cancellationStarted.countDown();
            return invocation.callRealMethod();
        }).when(transactions).cancelBooking(anyLong(), anyLong());
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // when
            final var creation = executor.submit(() -> create(validRequest()));
            assertThat(confirmationReady.await(2, SECONDS)).isTrue();
            final var cancellation = executor.submit(() -> cancel(bookingId.get()));
            assertThat(cancellationStarted.await(2, SECONDS)).isTrue();
            allowConfirmationCommit.countDown();
            final var created = creation.get(2, SECONDS);
            final var cancelled = cancellation.get(2, SECONDS);

            // then
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
            assertThat(bookingRepository.findById(bookingId.get()).orElseThrow().getStatus()).isEqualTo(CANCELLED);
            assertThat(requests("release")).hasSize(1);
            assertThat(requests("release").getFirst().body().get("reason").asString()).isEqualTo("USER_CANCELLATION");
        } finally {
            allowConfirmationCommit.countDown();
        }
    }

    @Test
    @SneakyThrows
    void shouldReportCancellationWhenHotelRejectsLateConfirmation() {
        HOTEL_HANDLER.set(request -> {
            if (request.path().endsWith("confirm-availability")) {
                assertThat(cancel(request.body().get("bookingId").longValue()).getResponse().getStatus()).isEqualTo(200);
                respond(request, 409, Map.of("code", "RESERVATION_RELEASED"));
            } else {
                respondSuccessfully(request);
            }
        });

        final var result = create(validRequest());

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asString())
            .isEqualTo("BOOKING_CANCELLED");
        assertThat(bookingRepository.findAll().getFirst().getStatus()).isEqualTo(CANCELLED);
        assertThat(bookingRepository.findAll().getFirst().isReleasePending()).isFalse();
        assertThat(requests("release")).hasSize(1);
    }

    @Test
    @SneakyThrows
    void shouldRetainCompensationWhenDeadlineExpiresBeforeLocalConfirmation() {
        HOTEL_HANDLER.set(request -> {
            CLOCK_OFFSET.set(Duration.ofSeconds(10));
            respondSuccessfully(request);
        });

        final var result = create(validRequest());

        assertThat(result.getResponse().getStatus()).isEqualTo(504);
        assertThat(bookingRepository.findAll().getFirst().getStatus()).isEqualTo(CANCELLED);
        assertThat(bookingRepository.findAll().getFirst().isReleasePending()).isTrue();
        assertThat(requests("release")).isEmpty();
    }

    @Test
    void shouldBoundRequestTimeWhenResponseHeadersNeverArrive() {
        // given
        final var now = clock.instant();
        final var booking = bookingRepository.saveAndFlush(new Booking(user, randomUUID(), false, null, 42L,
            LocalDate.now(clock), LocalDate.now(clock).plusDays(1), now, now.plusSeconds(5)));
        final var allowResponse = new CountDownLatch(1);
        HOTEL_HANDLER.set(request -> {
            await(allowResponse);
            respondSuccessfully(request);
        });
        try {
            // when
            final var startedAt = nanoTime();
            final var exception = catchThrowable(() -> hotelServiceGateway.confirm(booking, clock.instant().plusMillis(150)));
            final var elapsed = Duration.ofNanos(nanoTime() - startedAt);

            // then
            assertThat(exception).isInstanceOfSatisfying(HotelServiceException.class,
                failure -> assertThat(failure.getStatus()).isEqualTo(GATEWAY_TIMEOUT));
            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
            assertThat(requests("confirm-availability")).hasSize(1);
        } finally {
            allowResponse.countDown();
        }
    }

    @Test
    @SneakyThrows
    void shouldShareBookingWhenDuplicateRequestsStartTogether() {
        // given
        final var payload = validRequest();
        final var start = new CountDownLatch(1);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var futures = Stream.generate(() -> executor.submit(() -> {
                await(start);
                return create(payload);
            })).limit(12).toList();

            // when
            start.countDown();
            final var statuses = futures.stream().map(BookingCreationTest::getResponseStatus).toList();

            // then
            assertThat(statuses).allSatisfy(status -> assertThat(status).isIn(200, 201, 202));
            assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(1);
            assertThat(bookingRepository.count()).isEqualTo(1);
            assertThat(bookingRepository.findAll().getFirst().getStatus()).isEqualTo(CONFIRMED);
            assertThat(requests("confirm-availability")).hasSize(1);
        } finally {
            start.countDown();
        }
    }

    @Test
    @SneakyThrows
    void shouldCompensateWhenLocalConfirmationCannotBeSaved() {
        doThrow(new TransientDataAccessResourceException("private database diagnostic"))
            .when(transactions).confirm(anyLong());

        final var result = create(validRequest());
        final var booking = bookingRepository.findAll().getFirst();

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private database diagnostic");
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asString())
            .isEqualTo("BOOKING_STORAGE_UNAVAILABLE");
        assertThat(booking.getStatus()).isEqualTo(CANCELLED);
        assertThat(booking.isReleasePending()).isFalse();
        assertThat(requests("release")).hasSize(1);
    }

    @Test
    @SneakyThrows
    void shouldKeepPendingWhenCancellationCannotBeSaved() {
        HOTEL_HANDLER.set(request -> respond(request, 503, Map.of()));
        doThrow(new TransientDataAccessResourceException("private database diagnostic"))
            .when(transactions).cancelPending(anyLong());

        final var result = create(validRequest());
        final var booking = bookingRepository.findAll().getFirst();

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("bookingId").longValue())
            .isEqualTo(booking.getId());
        assertThat(booking.getStatus()).isEqualTo(PENDING);
        assertThat(requests("release")).isEmpty();
    }

    @Test
    @SneakyThrows
    void shouldRetainReleaseTaskWhenCompletionCannotBeSaved() {
        HOTEL_HANDLER.set(request -> {
            if (request.path().endsWith("confirm-availability")) {
                respond(request, 409, Map.of("code", "ROOM_UNAVAILABLE"));
            } else {
                respondSuccessfully(request);
            }
        });
        doThrow(new TransientDataAccessResourceException("private database diagnostic"))
            .when(transactions).completeRelease(anyLong());

        final var result = create(validRequest());
        final var booking = bookingRepository.findAll().getFirst();

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(booking.getStatus()).isEqualTo(CANCELLED);
        assertThat(booking.isReleasePending()).isTrue();
        assertThat(requests("release")).hasSize(1);
    }

    @Test
    void shouldBoundRequestTimeWhenResponseBodyDoesNotComplete() {
        // given
        final var now = clock.instant();
        final var booking = bookingRepository.saveAndFlush(new Booking(user, randomUUID(), false, null, 42L,
            LocalDate.now(clock), LocalDate.now(clock).plusDays(1), now, now.plusSeconds(5)));
        final var allowResponse = new CountDownLatch(1);
        HOTEL_HANDLER.set(request -> sendHeadersAndWait(request, allowResponse));
        try {
            // when
            final var startedAt = nanoTime();
            final var exception = catchThrowable(() -> hotelServiceGateway.confirm(booking, clock.instant().plusMillis(150)));
            final var elapsed = Duration.ofNanos(nanoTime() - startedAt);

            // then
            assertThat(exception).isInstanceOfSatisfying(HotelServiceException.class,
                failure -> assertThat(failure.getStatus()).isEqualTo(GATEWAY_TIMEOUT));
            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        } finally {
            allowResponse.countDown();
        }
    }

    @Test
    void shouldReportDependencyFailureWhenServiceRegistryIsEmpty() {
        final var client = new HotelServiceGateway(hotelRestClientBuilder.clone().baseUrl("http://missing-hotel"),
            hotelHttpClient, properties, internalTokenService, clock, JSON);
        final var now = clock.instant();
        final var booking = bookingRepository.saveAndFlush(new Booking(user, randomUUID(), false, null, 42L,
            LocalDate.now(clock), LocalDate.now(clock).plusDays(1), now, now.plusSeconds(5)));

        final var exception = catchThrowable(() -> client.confirm(booking, booking.getDeadlineAt()));

        assertThat(exception).isInstanceOfSatisfying(HotelServiceException.class,
            failure -> assertThat(failure.getCode()).isEqualTo("HOTEL_SERVICE_UNAVAILABLE"));
        assertThat(HOTEL_REQUESTS).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestId", "autoSelect", "roomId", "startDate", "endDate"})
    @SneakyThrows
    void shouldRejectCreationWhenRequiredFieldIsAbsent(String field) {
        final var payload = validRequest();
        payload.remove(field);

        final var result = create(payload);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(bookingRepository.count()).isZero();
        assertThat(HOTEL_REQUESTS).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    @SneakyThrows
    void shouldRejectCreationWhenPayloadIsInvalid(Map<String, Object> overrides) {
        final var payload = validRequest();
        payload.putAll(overrides);

        final var result = create(payload);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(bookingRepository.count()).isZero();
        assertThat(HOTEL_REQUESTS).isEmpty();
    }

    @Test
    @SneakyThrows
    void shouldRejectCreationWhenUserNoLongerExists() {
        final var token = tokenService.issueToken(user.getId(), USER);
        userRepository.delete(user);

        final var result = create(validRequest(), token);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asString())
            .isEqualTo("USER_NOT_FOUND");
        assertThat(bookingRepository.count()).isZero();
        assertThat(HOTEL_REQUESTS).isEmpty();
    }

    @Test
    @SneakyThrows
    void shouldRejectCreationWhenTokenIsMissing() {
        final var payload = JSON.writeValueAsBytes(validRequest());

        final var result = mockMvc.perform(post("/api/v1/bookings").contentType(APPLICATION_JSON).content(payload));

        result.andExpect(status().isUnauthorized());
        assertThat(bookingRepository.count()).isZero();
        assertThat(HOTEL_REQUESTS).isEmpty();
    }

    @Test
    @SneakyThrows
    void shouldRejectCreationWhenRoleIsAdmin() {
        final var token = tokenService.issueToken(user.getId(), ADMIN);

        final var result = create(validRequest(), token);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(bookingRepository.count()).isZero();
        assertThat(HOTEL_REQUESTS).isEmpty();
    }

    @Test
    @SneakyThrows
    void shouldRejectCreationWhenTokenIsInternal() {
        final var token = internalTokenService.issueToken();

        final var result = create(validRequest(), token);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(bookingRepository.count()).isZero();
        assertThat(HOTEL_REQUESTS).isEmpty();
    }

    private Map<String, Object> validRequest() {
        final var payload = new LinkedHashMap<String, Object>();
        payload.put("requestId", randomUUID().toString());
        payload.put("autoSelect", false);
        payload.put("roomId", 42L);
        payload.put("startDate", LocalDate.now(clock).toString());
        payload.put("endDate", LocalDate.now(clock).plusDays(1).toString());
        return payload;
    }

    private MvcResult create(Map<String, Object> payload) {
        return create(payload, tokenService.issueToken(user.getId(), USER));
    }

    @SneakyThrows
    private MvcResult create(Map<String, Object> payload, String token) {
        return mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, "Bearer " + token)
            .contentType(APPLICATION_JSON).content(JSON.writeValueAsBytes(payload))).andReturn();
    }

    @SneakyThrows
    private MvcResult cancel(long bookingId) {
        return mockMvc.perform(delete("/api/v1/bookings/{id}", bookingId)
            .header(AUTHORIZATION, "Bearer " + tokenService.issueToken(user.getId(), USER))).andReturn();
    }

    private static List<HotelRequest> requests(String operation) {
        return HOTEL_REQUESTS.stream().filter(request -> request.path().endsWith(operation)).toList();
    }

    @SneakyThrows
    private static int getResponseStatus(Future<MvcResult> result) {
        return result.get(5, SECONDS).getResponse().getStatus();
    }

    @SneakyThrows
    private static HttpServer startHotelServer() {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(HOTEL_EXECUTOR);
        server.createContext("/", BookingCreationTest::handleHotelRequest);
        server.start();
        return server;
    }

    @SneakyThrows
    private static void handleHotelRequest(HttpExchange exchange) {
        final var token = SignedJWT.parse(exchange.getRequestHeaders().getFirst(AUTHORIZATION).substring(7));
        final var request = new HotelRequest(exchange, exchange.getRequestURI().getPath(),
            JSON.readTree(exchange.getRequestBody().readAllBytes()), token.getJWTClaimsSet(),
            token.verify(JWT_VERIFIER.get()));
        HOTEL_REQUESTS.add(request);
        HOTEL_HANDLER.get().accept(request);
    }

    private static void respondSuccessfully(HotelRequest request) {
        respond(request, 200, Map.of("bookingId", request.body().get("bookingId").longValue(),
            "roomId", Long.parseLong(request.path().split("/")[4]),
            "state", request.path().endsWith("release")
                ? "RELEASED"
                : "RESERVED"));
    }

    private static void respond(HotelRequest request, int status, Object body) {
        final var bytes = body instanceof String text
            ? text.getBytes(UTF_8)
            : JSON.writeValueAsBytes(body);
        try (final var exchange = request.exchange()) {
            exchange.getResponseHeaders().set("Content-Type", APPLICATION_JSON.toString());
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException exception) {
            request.exchange().close();
        }
    }

    @SneakyThrows
    private static void sendHeadersAndWait(HotelRequest request, CountDownLatch allowResponse) {
        try (final var exchange = request.exchange()) {
            exchange.getResponseHeaders().set("Content-Type", APPLICATION_JSON.toString());
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            await(allowResponse);
        } catch (IOException exception) {
            request.exchange().close();
        }
    }

    @SneakyThrows
    private static void await(CountDownLatch latch) {
        if (!latch.await(4, SECONDS)) {
            throw new IllegalStateException("Test response gate was not released");
        }
    }

    private static Stream<Map<String, Object>> invalidRequests() {
        final var missingRequestId = new LinkedHashMap<String, Object>();
        missingRequestId.put("requestId", null);
        final var missingAutoSelect = new LinkedHashMap<String, Object>();
        missingAutoSelect.put("autoSelect", null);
        final var missingRoom = new LinkedHashMap<String, Object>();
        missingRoom.put("roomId", null);
        final var missingStartDate = new LinkedHashMap<String, Object>();
        missingStartDate.put("startDate", null);
        return Stream.of(
            missingRequestId, missingAutoSelect, missingRoom, missingStartDate,
            Map.of("requestId", "invalid"),
            Map.of("autoSelect", "false"),
            Map.of("autoSelect", 0),
            Map.of("autoSelect", true, "hotelId", 11),
            Map.of("roomId", 0),
            Map.of("roomId", -1),
            Map.of("roomId", 1.5),
            Map.of("roomId", "42"),
            Map.of("roomId", true),
            Map.of("hotelId", 11),
            Map.of("startDate", "2020-01-01"),
            Map.of("startDate", "2999-01-01", "endDate", "2999-01-01"),
            Map.of("startDate", "2999-01-02", "endDate", "2999-01-01"),
            Map.of("startDate", "2027-02-29"),
            Map.of("startDate", "2027-01-10T00:00:00Z"),
            Map.of("startDate", List.of(2027, 1, 10)),
            Map.of("userId", 99),
            Map.of("status", "CONFIRMED")
        );
    }

    private record HotelRequest(HttpExchange exchange, String path, JsonNode body,
                                JWTClaimsSet claims, boolean signatureVerified) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        private static Clock bookingClock() {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return Clock.offset(Clock.system(zone), CLOCK_OFFSET.get());
                }

                @Override
                public Instant instant() {
                    return Instant.now().plus(CLOCK_OFFSET.get());
                }
            };
        }
    }
}
