package com.example.hotel.hotel;

import java.net.URI;

import javax.sql.DataSource;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static java.net.http.HttpClient.newHttpClient;
import static java.net.http.HttpRequest.newBuilder;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = "eureka.client.enabled=false"
)
class HotelServiceApplicationTests {
    @LocalServerPort
    private int applicationPort;

    @Autowired
    private DataSource dataSource;

    @Test
    @SneakyThrows
    void shouldReportUpWhenHealthEndpointIsRequested() {
        try (var httpClient = newHttpClient()) {
            var healthRequest = newBuilder(URI.create("http://127.0.0.1:" + applicationPort + "/actuator/health")).build();

            var healthResponse = httpClient.send(healthRequest, ofString());

            assertThat(healthResponse.statusCode()).isEqualTo(200);
            assertThat(healthResponse.body()).contains("\"status\":\"UP\"");
        }
    }

    @Test
    @SneakyThrows
    void shouldConnectToDb() {
        var expectedDatabaseUrl = "jdbc:h2:mem:hotel_service";

        try (var connection = dataSource.getConnection()) {
            var databaseUrl = connection.getMetaData().getURL();
            var connectionIsValid = connection.isValid(1);

            assertThat(databaseUrl).isEqualTo(expectedDatabaseUrl);
            assertThat(connectionIsValid).isTrue();
        }
    }
}
