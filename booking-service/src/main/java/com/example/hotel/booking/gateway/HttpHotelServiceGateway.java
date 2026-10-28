package com.example.hotel.booking.gateway;

import com.example.hotel.booking.controller.dto.RoomPageResponse;
import com.example.hotel.booking.domain.Booking;
import com.example.hotel.booking.gateway.exception.HotelServiceException;
import com.example.hotel.booking.properties.HotelClientProperties;
import com.example.hotel.booking.security.InternalTokenService;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static com.example.hotel.booking.domain.BookingReleaseReason.COMPENSATION;
import static java.lang.System.nanoTime;
import static java.time.Duration.ofMillis;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@Component
@Slf4j
public class HttpHotelServiceGateway implements HotelServiceGateway {
    private final RestClient.Builder hotelRestClientBuilder;
    private final HttpClient hotelHttpClient;
    private final HotelClientProperties properties;
    private final InternalTokenService tokenService;
    private final Clock clock;
    private final JsonMapper mapper;
    private final CircuitBreaker circuitBreaker;

    public HttpHotelServiceGateway(@LoadBalanced RestClient.Builder hotelRestClientBuilder,
                                   HttpClient hotelHttpClient,
                                   HotelClientProperties properties,
                                   InternalTokenService tokenService,
                                   Clock clock,
                                   JsonMapper mapper,
                                   CircuitBreaker circuitBreaker) {
        this.hotelRestClientBuilder = hotelRestClientBuilder;
        this.hotelHttpClient = hotelHttpClient;
        this.properties = properties;
        this.tokenService = tokenService;
        this.clock = clock;
        this.mapper = mapper;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public void confirm(Booking booking, Instant deadline) {
        executeRequest(booking, "confirm-availability", deadline, client -> {
            sendReservation(client, booking, "confirm-availability", Map.of(
                "bookingId", booking.getId(),
                "requestId", booking.getRequestId(),
                "startDate", booking.getStartDate(),
                "endDate", booking.getEndDate()
            ), "RESERVED");
            return null;
        });
    }

    @Override
    public void release(Booking booking, Instant deadline) {
        try {
            executeRequest(booking, "release", deadline, client -> {
                final var reason = booking.getReleaseReason() == null
                    ? COMPENSATION
                    : booking.getReleaseReason();
                sendReservation(client, booking, "release", Map.of(
                    "bookingId", booking.getId(),
                    "requestId", booking.getRequestId(),
                    "reason", reason
                ), "RELEASED");
                return null;
            });
        } catch (HotelServiceException exception) {
            if (exception.getStatus() != NOT_FOUND) {
                throw exception;
            }
        }
    }

    @Override
    public List<Long> recommend(Booking booking, String userToken, Instant deadline, Set<Long> excludedRoomIds) {
        final var response = readRecommendations(booking, booking.getStartDate(), booking.getEndDate(),
            booking.getHotelId(), 0, 20, userToken, deadline, excludedRoomIds);
        return response.content().stream().map(RoomPageResponse.RoomResponse::id).toList();
    }

    @Override
    public RoomPageResponse recommend(LocalDate startDate, LocalDate endDate, Long hotelId,
                                      int page, int size, String userToken) {
        return readRecommendations(null, startDate, endDate, hotelId, page, size, userToken,
            clock.instant().plusMillis(properties.operationTimeoutMs()), Set.of());
    }

    private RoomPageResponse readRecommendations(Booking booking, LocalDate startDate, LocalDate endDate,
                                                 Long hotelId, int page, int size, String userToken,
                                                 Instant deadline, Set<Long> excludedRoomIds) {
        return executeRequest(booking, "recommend", deadline, client -> {
            final var response = client.get()
                .uri(builder -> builder.path("/api/v1/rooms/recommend")
                    .queryParamIfPresent("hotelId", Optional.ofNullable(hotelId))
                    .queryParam("startDate", startDate)
                    .queryParam("endDate", endDate)
                    .queryParam("page", page)
                    .queryParam("size", size)
                    .queryParamIfPresent("excludedRoomIds", Optional.of(excludedRoomIds).filter(ids -> !ids.isEmpty()))
                    .build())
                .headers(headers -> headers.setBearerAuth(userToken))
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), this::handleError)
                .body(RoomPageResponse.class);

            if (response == null || response.content() == null || response.content().size() > size) {
                throw HotelServiceException.unavailable(false);
            }
            final var validRooms = response.content().stream()
                .allMatch(room -> room != null && room.id() > 0 && !excludedRoomIds.contains(room.id()));
            if (!validRooms) {
                throw HotelServiceException.unavailable(false);
            }
            final var distinctRooms = response.content().stream().map(RoomPageResponse.RoomResponse::id).distinct().count();
            if (distinctRooms != response.content().size()) {
                throw HotelServiceException.unavailable(false);
            }
            return response;
        });
    }

    private <T> T executeRequest(Booking booking, String operation, Instant deadline, Function<RestClient, T> request) {
        try {
            return circuitBreaker.executeSupplier(() -> executeWithRetries(booking, operation, deadline, request));
        } catch (CallNotPermittedException exception) {
            throw HotelServiceException.unavailable(false);
        }
    }

    private <T> T executeWithRetries(Booking booking, String operation, Instant deadline, Function<RestClient, T> request) {
        final var bookingId = booking == null ? null : booking.getId();
        final var requestId = booking == null ? null : booking.getRequestId();
        final var roomId = booking == null ? null : booking.getRoomId();
        final var budget = Duration.between(clock.instant(), deadline);
        if (budget.isNegative()
            || budget.isZero()
            || Thread.currentThread().isInterrupted()) {
            throw HotelServiceException.timeout();
        }
        final var startedAt = nanoTime();

        for (var attempt = 1; ; attempt++) {
            final var remaining = budget.minusNanos(nanoTime() - startedAt);
            if (remaining.isNegative() || remaining.isZero()) {
                throw HotelServiceException.timeout();
            }

            log.info("Hotel request bookingId={} requestId={} roomId={} operation={} attempt={}",
                bookingId, requestId, roomId, operation, attempt);
            try {
                return send(request, remaining);
            } catch (HotelServiceException exception) {
                log.warn("Hotel request failed bookingId={} requestId={} roomId={} operation={} attempt={} code={}",
                    bookingId, requestId, roomId, operation, attempt, exception.getCode());
                if (budget.minusNanos(nanoTime() - startedAt).isNegative()) {
                    throw HotelServiceException.timeout();
                }
                if (!exception.isRetryable() || attempt >= properties.maxAttempts()) {
                    throw exception;
                }
                pauseBeforeRetry(attempt, budget.minusNanos(nanoTime() - startedAt));
            }
        }
    }

    private <T> T send(Function<RestClient, T> request, Duration remaining) {
        final var factory = new JdkClientHttpRequestFactory(hotelHttpClient);
        final var readTimeout = remaining.compareTo(ofMillis(properties.readTimeoutMs())) < 0
            ? remaining
            : ofMillis(properties.readTimeoutMs());
        factory.setReadTimeout(readTimeout);
        final var client = hotelRestClientBuilder.clone()
            .requestFactory(factory)
            .build();

        try {
            return request.apply(client);
        } catch (ResourceAccessException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw HotelServiceException.timeout();
            }
            if (exception.getMostSpecificCause() instanceof HttpTimeoutException) {
                throw new HotelServiceException(GATEWAY_TIMEOUT, "BOOKING_TIMEOUT", "Hotel Service request timed out", true);
            }
            throw HotelServiceException.unavailable(true);
        } catch (RestClientException exception) {
            throw HotelServiceException.unavailable(exception.getMostSpecificCause() instanceof IOException);
        } catch (JacksonException exception) {
            throw HotelServiceException.unavailable(false);
        } catch (IllegalStateException exception) {
            throw HotelServiceException.unavailable(true);
        }
    }

    private void sendReservation(RestClient client, Booking booking, String operation, Object body, String expectedState) {
        final var response = client.post()
            .uri("/api/v1/rooms/{id}/{operation}", booking.getRoomId(), operation)
            .headers(headers -> headers.setBearerAuth(tokenService.issueToken()))
            .contentType(APPLICATION_JSON)
            .body(body)
            .retrieve()
            .onStatus(status -> !status.is2xxSuccessful(), this::handleError)
            .body(ReservationReply.class);

        if (response == null
            || response.bookingId() != booking.getId()
            || response.roomId() != booking.getRoomId()
            || !expectedState.equals(response.state())) {
            throw HotelServiceException.unavailable(false);
        }
    }

    private void handleError(HttpRequest request, ClientHttpResponse reply) throws IOException {
        final var status = reply.getStatusCode().value();
        if (status == 404 || status == 409) {
            final var problem = mapper.readTree(reply.getBody());
            if (problem != null) {
                final var code = problem.path("code").asString();
                if (status == 404 && "ROOM_NOT_FOUND".equals(code)) {
                    throw new HotelServiceException(NOT_FOUND, "ROOM_NOT_FOUND", "Room not found", false);
                }
                if (status == 409 && "ROOM_UNAVAILABLE".equals(code)) {
                    throw new HotelServiceException(CONFLICT, "ROOM_UNAVAILABLE", "Room is unavailable", false);
                }
                if (status == 409 && "RESERVATION_RELEASED".equals(code)) {
                    throw new HotelServiceException(CONFLICT, "BOOKING_CANCELLED",
                        "Booking was cancelled during confirmation", false);
                }
            }
        }
        throw HotelServiceException.unavailable(status == 502 || status == 503 || status == 504);
    }

    private void pauseBeforeRetry(int attempt, Duration remaining) {
        final var maximumBackoff = Math.min(1000, properties.backoffMs() * (1L << (attempt - 1)));
        final var delay = ofMillis(ThreadLocalRandom.current().nextLong(maximumBackoff / 2, maximumBackoff + 1));
        if (remaining.compareTo(delay) <= 0) {
            throw HotelServiceException.timeout();
        }

        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw HotelServiceException.timeout();
        }
    }

    private record ReservationReply(
                                    long bookingId,
                                    long roomId,
                                    String state
                                    ) {
    }
}
