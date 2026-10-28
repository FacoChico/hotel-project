package com.example.hotel.gateway;

import com.example.hotel.gateway.testutil.JwtTestTokens;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;

import lombok.SneakyThrows;

import static com.example.hotel.gateway.testutil.JwtTestTokens.USER_TOKEN;
import static java.net.http.HttpClient.newHttpClient;
import static java.net.http.HttpRequest.newBuilder;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "eureka.client.enabled=false")
class GatewayFailureTest {
    @LocalServerPort
    private int applicationPort;

    @DynamicPropertySource
    private static void registerKeys(DynamicPropertyRegistry registry) {
        JwtTestTokens.register(registry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hotels", "bookings"})
    @SneakyThrows
    void shouldReturnServiceUnavailableWhenDiscoveryHasNoBackend(String resource) {
        try (final var client = newHttpClient()) {
            final var request = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + "/api/v1/" + resource))
                .header("Authorization", "Bearer " + USER_TOKEN)
                .build();

            final var response = client.send(request, ofString());

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).startsWith("application/problem+json"));
            assertThat(response.body()).contains("SERVICE_UNAVAILABLE").doesNotContain("Unable to find instance");
        }
    }
}
