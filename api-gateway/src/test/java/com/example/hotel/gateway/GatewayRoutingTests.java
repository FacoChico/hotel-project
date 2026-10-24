package com.example.hotel.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static java.net.http.HttpClient.newHttpClient;
import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static java.net.http.HttpRequest.newBuilder;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = {
        "eureka.client.enabled=false",
        "spring.cloud.loadbalancer.cache.enabled=false"
    }
)
class GatewayRoutingTests {
    private static final AtomicInteger BACKEND_REQUEST_COUNT = new AtomicInteger();
    private static final HttpServer HOTEL_BACKEND_SERVER = startTestBackendServer("hotel-service");
    private static final HttpServer BOOKING_BACKEND_SERVER = startTestBackendServer("booking-service");

    @LocalServerPort
    private int applicationPort;

    @DynamicPropertySource
    static void registerTestBackendInstances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.hotel-service[0].uri",
            () -> "http://127.0.0.1:" + HOTEL_BACKEND_SERVER.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.booking-service[0].uri",
            () -> "http://127.0.0.1:" + BOOKING_BACKEND_SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopTestBackendServers() {
        HOTEL_BACKEND_SERVER.stop(0);
        BOOKING_BACKEND_SERVER.stop(0);
    }

    @SneakyThrows
    @ParameterizedTest
    @CsvSource({
        "GET, /api/hotels, hotel-service",
        "POST, /api/hotels, hotel-service",
        "PATCH, /api/hotels/1, hotel-service",
        "DELETE, /api/hotels/1, hotel-service",
        "GET, /api/rooms, hotel-service",
        "POST, /api/rooms, hotel-service",
        "PATCH, /api/rooms/7, hotel-service",
        "DELETE, /api/rooms/7, hotel-service",
        "GET, /api/rooms/recommend, hotel-service",
        "GET, /api/rooms/statistics, hotel-service",
        "POST, /api/users/register, booking-service",
        "POST, /api/users/auth, booking-service",
        "GET, /api/users, booking-service",
        "POST, /api/users, booking-service",
        "PATCH, /api/users/1, booking-service",
        "DELETE, /api/users/1, booking-service",
        "POST, /api/bookings, booking-service",
        "GET, /api/bookings, booking-service",
        "GET, /api/bookings/abc, booking-service",
        "DELETE, /api/bookings/abc, booking-service"
    })
    void shouldForwardRequestWhenPublicRouteMatches(String httpMethod, String requestPath, String expectedServiceName) {
        try (var httpClient = newHttpClient()) {
            var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + requestPath + "?page=1&size=5"))
                .header("Authorization", "Bearer routing-test-marker")
                .method(httpMethod, noBody())
                .build();

            var response = httpClient.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(expectedServiceName + "|" + httpMethod + "|" + requestPath
                + "|page=1&size=5|Bearer routing-test-marker");
        }
    }

    @SneakyThrows
    @ParameterizedTest
    @CsvSource({
        "POST, /api/rooms/7/confirm-availability",
        "POST, /api/rooms/7/release",
        "GET, /api/rooms/7/release",
        "POST, /api/rooms/7",
        "PUT, /api/hotels/1",
        "GET, /hotel-service/actuator/health",
        "GET, /booking-service/actuator/info",
        "GET, /eureka/apps",
        "GET, /api/rooms/7/actuator/health"
    })
    void shouldRejectRequestWhenRouteIsInternalOrUnlisted(String httpMethod, String requestPath) {
        try (var httpClient = newHttpClient()) {
            var backendRequestCountBeforeRequest = BACKEND_REQUEST_COUNT.get();
            var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + requestPath))
                .method(httpMethod, noBody())
                .build();

            var response = httpClient.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(BACKEND_REQUEST_COUNT.get()).isEqualTo(backendRequestCountBeforeRequest);
        }
    }

    private static HttpServer startTestBackendServer(String serviceName) {
        try {
            var backendServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backendServer.createContext("/", exchange -> {
                BACKEND_REQUEST_COUNT.incrementAndGet();
                byte[] responseBody = (serviceName + "|" + exchange.getRequestMethod() + "|"
                    + exchange.getRequestURI().getRawPath() + "|"
                    + exchange.getRequestURI().getRawQuery() + "|"
                    + exchange.getRequestHeaders().getFirst("Authorization")).getBytes(UTF_8);
                exchange.sendResponseHeaders(200, responseBody.length);
                try (var responseOutput = exchange.getResponseBody()) {
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
