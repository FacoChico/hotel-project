package com.example.hotel.discovery;

import com.netflix.eureka.cluster.PeerEurekaNodes;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;

import lombok.SneakyThrows;

import static java.net.http.HttpClient.newHttpClient;
import static java.net.http.HttpRequest.newBuilder;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
class DiscoveryServerApplicationTest {
    @LocalServerPort
    private int applicationPort;

    @Autowired
    private PeerEurekaNodes replicationPeers;

    @Test
    void shouldHaveNoReplicationPeersWhenRunningStandalone() {
        var configuredReplicationPeers = replicationPeers.getPeerEurekaNodes();

        assertThat(configuredReplicationPeers).isEmpty();
    }

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
}
