package com.example.hotel.booking.gateway;

import com.example.hotel.booking.domain.Booking;
import com.example.hotel.booking.gateway.exception.HotelServiceException;
import com.example.hotel.booking.properties.HotelClientProperties;
import com.example.hotel.booking.security.InternalTokenService;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
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
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static java.lang.System.nanoTime;
import static java.time.Duration.ofMillis;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@Component
@Slf4j
public class HotelServiceGateway {
    private final RestClient.Builder hotelRestClientBuilder;
    private final HttpClient hotelHttpClient;
    private final HotelClientProperties properties;
    private final InternalTokenService tokenService;
    private final Clock clock;
    private final JsonMapper mapper;

    public HotelServiceGateway(@LoadBalanced RestClient.Builder hotelRestClientBuilder,
                               HttpClient hotelHttpClient,
                               HotelClientProperties properties,
                               InternalTokenService tokenService,
                               Clock clock,
                               JsonMapper mapper) {
        this.hotelRestClientBuilder = hotelRestClientBuilder;
        this.hotelHttpClient = hotelHttpClient;
        this.properties = properties;
        this.tokenService = tokenService;
        this.clock = clock;
        this.mapper = mapper;
    }

    public void confirm(Booking booking, Instant deadline) {
        executeRequest(
            booking,
            "confirm-availability",
            Map.of(
                "bookingId", booking.getId(),
                "requestId", booking.getRequestId(),
                "startDate", booking.getStartDate(),
                "endDate", booking.getEndDate()
            ),
            "RESERVED",
            deadline
        );
    }

    public void release(Booking booking, Instant deadline) {
        try {
            executeRequest(
                booking,
                "release",
                Map.of(
                    "bookingId", booking.getId(),
                    "requestId", booking.getRequestId(),
                    "reason", booking.getReleaseReason()
                ),
                "RELEASED",
                deadline
            );
        } catch (HotelServiceException exception) {
            if (exception.getStatus() != NOT_FOUND) {
                throw exception;
            }
        }
    }

    private void executeRequest(Booking booking, String operation, Object body, String expectedState, Instant deadline) {
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
                booking.getId(), booking.getRequestId(), booking.getRoomId(), operation, attempt);
            try {
                send(booking, operation, body, expectedState, remaining);
                return;
            } catch (HotelServiceException exception) {
                log.warn("Hotel request failed bookingId={} requestId={} roomId={} operation={} attempt={} code={}",
                    booking.getId(), booking.getRequestId(), booking.getRoomId(), operation, attempt, exception.getCode());
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

    private void send(Booking booking, String operation, Object body, String expectedState, Duration remaining) {
        final var factory = new JdkClientHttpRequestFactory(hotelHttpClient);
        final var readTimeout = remaining.compareTo(ofMillis(properties.readTimeoutMs())) < 0
            ? remaining
            : ofMillis(properties.readTimeoutMs());
        factory.setReadTimeout(readTimeout);
        final var client = hotelRestClientBuilder.clone()
            .requestFactory(factory)
            .build();

        try {
            final var response = client.post()
                .uri("/api/v1/rooms/{id}/{operation}", booking.getRoomId(), operation)
                .headers(headers -> headers.setBearerAuth(tokenService.issueToken()))
                .contentType(APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (request, reply) -> {
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
                        }
                    }
                    throw HotelServiceException.unavailable(status == 502 || status == 503 || status == 504);
                })
                .body(ReservationReply.class);

            if (response == null
                || response.bookingId() != booking.getId()
                || response.roomId() != booking.getRoomId()
                || !expectedState.equals(response.state())) {
                throw HotelServiceException.unavailable(false);
            }
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
