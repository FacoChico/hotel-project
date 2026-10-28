package com.example.hotel.gateway;

import com.example.hotel.gateway.testutil.JwtTestTokens;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import lombok.SneakyThrows;

import static com.example.hotel.gateway.testutil.JwtTestTokens.USER_TOKEN;
import static java.net.http.HttpClient.newHttpClient;
import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static java.net.http.HttpRequest.newBuilder;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = {
        "eureka.client.enabled=false",
        "spring.cloud.loadbalancer.cache.enabled=false"
    }
)
class GatewayRoutingTest {
    private static final AtomicReference<String> LAST_BACKEND_TRACEPARENT = new AtomicReference<>();
    private static final AtomicInteger BACKEND_REQUEST_COUNT = new AtomicInteger();
    private static final HttpServer HOTEL_BACKEND_SERVER = startTestBackendServer("hotel-service");
    private static final HttpServer BOOKING_BACKEND_SERVER = startTestBackendServer("booking-service");

    @LocalServerPort
    private int applicationPort;

    @DynamicPropertySource
    static void registerTestBackendInstances(DynamicPropertyRegistry registry) {
        JwtTestTokens.register(registry);
        registry.add("spring.cloud.discovery.client.simple.instances.hotel-service[0].uri",
            () -> "http://127.0.0.1:" + HOTEL_BACKEND_SERVER.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.booking-service[0].uri",
            () -> "http://127.0.0.1:" + BOOKING_BACKEND_SERVER.getAddress().getPort());
    }

    @Test
    @SneakyThrows
    void shouldPropagateTraceWhenAuthenticatedRequestIsForwarded() {
        final var traceId = "11111111111111111111111111111111";
        final var traceparent = "00-" + traceId + "-2222222222222222-01";
        try (final var client = newHttpClient()) {
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + "/api/v1/hotels"))
                .header("Authorization", "Bearer " + USER_TOKEN)
                .header("traceparent", traceparent)
                .build();

            final var response = client.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Trace-Id")).contains(traceId);
            assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(LAST_BACKEND_TRACEPARENT.get()).startsWith("00-" + traceId + "-");
        }
    }

    @Test
    @SneakyThrows
    void shouldRejectRequestWhenBearerTokenIsMissing() {
        final var requestsBefore = BACKEND_REQUEST_COUNT.get();
        try (final var client = newHttpClient()) {
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + "/api/v1/hotels")).build();

            final var response = client.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("UNAUTHORIZED");
            assertThat(BACKEND_REQUEST_COUNT.get()).isEqualTo(requestsBefore);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "internal", "expired"})
    @SneakyThrows
    void shouldRejectRequestWhenBearerTokenIsInvalid(String reason) {
        final var token = switch (reason) {
            case "internal" -> JwtTestTokens.token("hotel-internal", Instant.now().plusSeconds(120));
            case "expired" -> JwtTestTokens.token("hotel-api", Instant.now().minusSeconds(120));
            default -> "invalid";
        };
        final var requestsBefore = BACKEND_REQUEST_COUNT.get();
        try (final var client = newHttpClient()) {
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + "/api/v1/hotels"))
                .header("Authorization", "Bearer " + token).build();

            final var response = client.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(BACKEND_REQUEST_COUNT.get()).isEqualTo(requestsBefore);
        }
    }

    @AfterAll
    static void stopTestBackendServers() {
        HOTEL_BACKEND_SERVER.stop(0);
        BOOKING_BACKEND_SERVER.stop(0);
    }

    @SneakyThrows
    @ParameterizedTest
    @CsvSource({
        "GET, /api/v1/hotels, hotel-service",
        "POST, /api/v1/hotels, hotel-service",
        "PATCH, /api/v1/hotels/1, hotel-service",
        "DELETE, /api/v1/hotels/1, hotel-service",
        "GET, /api/v1/rooms, hotel-service",
        "POST, /api/v1/rooms, hotel-service",
        "PATCH, /api/v1/rooms/7, hotel-service",
        "DELETE, /api/v1/rooms/7, hotel-service",
        "GET, /api/v1/rooms/recommend, hotel-service",
        "GET, /api/v1/rooms/statistics, hotel-service",
        "POST, /api/v1/users/register, booking-service",
        "POST, /api/v1/users/auth, booking-service",
        "GET, /api/v1/users, booking-service",
        "POST, /api/v1/users, booking-service",
        "GET, /api/v1/users/1, booking-service",
        "PATCH, /api/v1/users/1, booking-service",
        "DELETE, /api/v1/users/1, booking-service",
        "POST, /api/v1/bookings, booking-service",
        "GET, /api/v1/bookings, booking-service",
        "GET, /api/v1/bookings/42, booking-service",
        "GET, /api/v1/bookings/recommendations, booking-service",
        "DELETE, /api/v1/bookings/42, booking-service"
    })
    void shouldForwardRequestWhenPublicRouteMatches(String httpMethod, String requestPath, String expectedServiceName) {
        try (final var httpClient = newHttpClient()) {
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + requestPath + "?page=1&size=5"))
                .header("Authorization", "Bearer " + USER_TOKEN)
                .method(httpMethod, noBody())
                .build();

            final var response = httpClient.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(expectedServiceName + "|" + httpMethod + "|" + requestPath
                + "|page=1&size=5|Bearer " + USER_TOKEN);
        }
    }

    @SneakyThrows
    @ParameterizedTest
    @CsvSource({
        "POST, /api/v1/rooms/7/confirm-availability",
        "POST, /api/v1/rooms/7/release",
        "GET, /api/v1/rooms/7/release",
        "POST, /api/v1/rooms/7",
        "PUT, /api/v1/hotels/1",
        "GET, /hotel-service/actuator/health",
        "GET, /booking-service/actuator/info",
        "GET, /eureka/apps",
        "POST, /user/register",
        "POST, /user/auth",
        "POST, /user",
        "POST, /api/v2/user/register",
        "GET, /api/v1/user",
        "PUT, /api/v1/user",
        "POST, /api/v1/user/unknown",
        "PATCH, /api/v1/user/7",
        "POST, /api/users/v1/register",
        "POST, /api/users/v1/auth",
        "GET, /api/users/v1",
        "GET, /api/users/v1/1",
        "GET, /api/hotels/v1",
        "GET, /api/rooms/v1",
        "GET, /api/bookings/v1",
        "POST, /user/v1/register",
        "POST, /user/v1/auth",
        "POST, /user/v1",
        "PATCH, /user/v1?id=7",
        "POST, /api/users/register",
        "POST, /api/users/auth",
        "GET, /api/hotels",
        "GET, /api/rooms",
        "GET, /api/users",
        "GET, /api/bookings",
        "POST, /api/rooms/7/release",
        "POST, /api/v2/users/register",
        "GET, /api/v2/rooms",
        "GET, /api/v1/rooms/7/actuator/health",
        "PATCH, /api/v1/rooms/recommend",
        "DELETE, /api/v1/rooms/recommend",
        "PATCH, /api/v1/rooms/statistics",
        "DELETE, /api/v1/rooms/statistics",
        "GET, /api/v1/users/auth",
        "GET, /api/v1/users/register",
        "GET, /api/v1/booking",
        "PATCH, /api/v1/booking/7",
        "POST, /api/v1/booking/7",
        "POST, /api/v2/booking",
        "GET, /api/v1/booking/7/release"
    })
    void shouldRejectRequestWhenRouteIsInternalOrUnlisted(String httpMethod, String requestPath) {
        try (final var httpClient = newHttpClient()) {
            final var backendRequestCountBeforeRequest = BACKEND_REQUEST_COUNT.get();
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + requestPath))
                .header("Authorization", "Bearer " + USER_TOKEN)
                .method(httpMethod, noBody())
                .build();

            final var response = httpClient.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(BACKEND_REQUEST_COUNT.get()).isEqualTo(backendRequestCountBeforeRequest);
        }
    }

    @ParameterizedTest
    @MethodSource("nonnumericEntityPaths")
    @SneakyThrows
    void shouldRejectRequestWhenEntityPathIdIsNotNumeric(String method, String path) {
        try (final var httpClient = newHttpClient()) {
            final var requestsBefore = BACKEND_REQUEST_COUNT.get();
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + path))
                .header("Authorization", "Bearer " + USER_TOKEN)
                .method(method, noBody()).build();

            final var response = httpClient.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(BACKEND_REQUEST_COUNT.get()).isEqualTo(requestsBefore);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "POST, /api/v1/user/register?trace=one, /api/v1/users/register, trace=one",
        "POST, /api/v1/user/auth?trace=one, /api/v1/users/auth, trace=one",
        "POST, /api/v1/user?trace=one, /api/v1/users, trace=one",
        "PATCH, /api/v1/user?id=7&trace=one, /api/v1/users/7, id=7&trace=one",
        "DELETE, /api/v1/user?id=7&trace=one, /api/v1/users/7, id=7&trace=one"
    })
    @SneakyThrows
    void shouldForwardBodyAndHeadersWhenUserAliasMatches(String method,
                                                         String path,
                                                         String expectedPath,
                                                         String expectedQuery) {
        final var payload = "{\"username\":\"routing_guest\"}";
        try (final var httpClient = newHttpClient()) {
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + path))
                .header("Authorization", "Bearer " + USER_TOKEN)
                .header("Content-Type", "application/json")
                .method(method, ofString(payload))
                .build();

            final var response = httpClient.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("booking-service|" + method + "|" + expectedPath
                + "|" + expectedQuery + "|Bearer " + USER_TOKEN + "|" + payload);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "PATCH, /api/v1/user", "DELETE, /api/v1/user?id=", "PATCH, /api/v1/user?id=0",
        "DELETE, /api/v1/user?id=-1", "PATCH, /api/v1/user?id=abc",
        "DELETE, /api/v1/user?id=9223372036854775808", "PATCH, /api/v1/user?id=7&id=8",
        "DELETE, /api/v1/user?id=7%2Frelease"
    })
    @SneakyThrows
    void shouldRejectAliasWhenIdQueryIsInvalid(String method, String path) {
        final var before = BACKEND_REQUEST_COUNT.get();
        try (final var httpClient = newHttpClient()) {
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + path))
                .header("Authorization", "Bearer " + USER_TOKEN)
                .method(method, noBody()).build();

            final var response = httpClient.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .contains("application/problem+json");
            assertThat(response.body()).contains("INVALID_REQUEST_PARAMETERS");
            assertThat(BACKEND_REQUEST_COUNT.get()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 409})
    @SneakyThrows
    void shouldPreserveErrorWhenBackendRejectsAliasRequest(int status) {
        try (final var httpClient = newHttpClient()) {
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + "/api/v1/user?id=" + status))
                .header("Authorization", "Bearer " + USER_TOKEN)
                .method("PATCH", noBody()).build();

            final var response = httpClient.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(status);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .contains("application/problem+json");
            assertThat(response.headers().firstValue("X-Test-Response").orElseThrow()).isEqualTo("preserved");
            assertThat(response.body()).isEqualTo("{\"code\":\"BACKEND_ERROR\"}");
        }
    }

    @ParameterizedTest
    @CsvSource({
        "POST, /api/v1/booking?trace=one, /api/v1/bookings",
        "GET, /api/v1/booking/7?trace=one, /api/v1/bookings/7",
        "DELETE, /api/v1/booking/7?trace=one, /api/v1/bookings/7",
        "GET, /api/v1/booking/0007?trace=one, /api/v1/bookings/0007"
    })
    @SneakyThrows
    void shouldForwardBookingAliasWhenMethodAndPathMatch(String method, String path, String expectedPath) {
        final var payload = method.equals("POST")
            ? "{\"roomId\":42}"
            : "";
        try (final var client = newHttpClient()) {
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + path))
                .header("Authorization", "Bearer " + USER_TOKEN)
                .header("Content-Type", "application/json")
                .method(method, ofString(payload))
                .build();

            final var response = client.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("booking-service|" + method + "|" + expectedPath
                + "|trace=one|Bearer " + USER_TOKEN + (payload.isEmpty()
                ? ""
                : "|" + payload));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "POST, /api/v1/booking, 201",
        "POST, /api/v1/booking, 202",
        "DELETE, /api/v1/booking/42, 200",
        "DELETE, /api/v1/booking/42, 202"
    })
    @SneakyThrows
    void shouldPreserveResponseWhenBookingAliasReturnsLocation(String method, String path, int expectedStatus) {
        try (final var client = newHttpClient()) {
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + path))
                .header("Authorization", "Bearer " + USER_TOKEN)
                .header("X-Test-Booking-Status", Integer.toString(expectedStatus))
                .method(method, noBody())
                .build();

            final var response = client.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(expectedStatus);
            assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo("/api/v1/bookings/42");
            assertThat(response.body()).isEqualTo("{\"id\":42}");
        }
    }

    @ParameterizedTest
    @CsvSource({
        "GET,401",
        "GET,403",
        "GET,404",
        "GET,409",
        "DELETE,401",
        "DELETE,403",
        "DELETE,404",
        "DELETE,409"
    })
    @SneakyThrows
    void shouldPreserveErrorWhenBackendRejectsBookingAlias(String method, int expectedStatus) {
        try (final var client = newHttpClient()) {
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + "/api/v1/booking/" + expectedStatus))
                .header("Authorization", "Bearer " + USER_TOKEN)
                .method(method, noBody()).build();

            final var response = client.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(expectedStatus);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
            assertThat(response.headers().firstValue("X-Test-Response").orElseThrow()).isEqualTo("preserved");
            assertThat(response.body()).isEqualTo("{\"code\":\"BACKEND_ERROR\"}");
        }
    }

    @ParameterizedTest
    @CsvSource({
        "GET,abc",
        "GET,-1",
        "GET,1.5",
        "DELETE,abc",
        "DELETE,-1",
        "DELETE,1.5"
    })
    @SneakyThrows
    void shouldRejectBookingAliasWhenIdIsNotNumeric(String method, String id) {
        final var requestsBefore = BACKEND_REQUEST_COUNT.get();
        try (final var client = newHttpClient()) {
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + "/api/v1/booking/" + id))
                .header("Authorization", "Bearer " + USER_TOKEN)
                .method(method, noBody()).build();

            final var response = client.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(BACKEND_REQUEST_COUNT.get()).isEqualTo(requestsBefore);
        }
    }

    private static Stream<Arguments> nonnumericEntityPaths() {
        return Stream.of("hotels", "rooms", "users", "bookings")
            .flatMap(resource -> (resource.equals("bookings")
                ? List.of("GET", "DELETE")
                : List.of("GET", "PATCH", "DELETE")).stream()
                .flatMap(method -> Stream.of("abc", "-1", "1.5")
                    .map(id -> arguments(method, "/api/v1/" + resource + "/" + id))));
    }

    private static HttpServer startTestBackendServer(String serviceName) {
        try {
            final var backendServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backendServer.createContext("/", exchange -> {
                LAST_BACKEND_TRACEPARENT.set(exchange.getRequestHeaders().getFirst("traceparent"));
                BACKEND_REQUEST_COUNT.incrementAndGet();
                final var path = exchange.getRequestURI().getPath();
                if (path.matches("/api/v1/(users|bookings)/(401|403|404|409)")) {
                    final var errorBody = "{\"code\":\"BACKEND_ERROR\"}".getBytes(UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
                    exchange.getResponseHeaders().set("X-Test-Response", "preserved");
                    final var status = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
                    exchange.sendResponseHeaders(status, errorBody.length);
                    try (final var output = exchange.getResponseBody()) {
                        output.write(errorBody);
                    }
                    return;
                }
                final var bookingStatus = exchange.getRequestHeaders().getFirst("X-Test-Booking-Status");
                final var bookingResponsePath = path.equals("/api/v1/bookings") || path.equals("/api/v1/bookings/42");
                if (bookingResponsePath && bookingStatus != null) {
                    final var responseBody = "{\"id\":42}".getBytes(UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("Location", "/api/v1/bookings/42");
                    exchange.sendResponseHeaders(Integer.parseInt(bookingStatus), responseBody.length);
                    try (final var output = exchange.getResponseBody()) {
                        output.write(responseBody);
                    }
                    return;
                }
                final var payload = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
                final var responseBody = (serviceName + "|" + exchange.getRequestMethod() + "|"
                    + exchange.getRequestURI().getRawPath() + "|"
                    + exchange.getRequestURI().getRawQuery() + "|"
                    + exchange.getRequestHeaders().getFirst("Authorization")
                    + (payload.isEmpty()
                    ? ""
                    : "|" + payload)).getBytes(UTF_8);
                exchange.sendResponseHeaders(200, responseBody.length);
                try (final var responseOutput = exchange.getResponseBody()) {
                    responseOutput.write(responseBody);
                }
            });
            backendServer.start();
            return backendServer;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start test backend server", exception);
        }
    }
}
