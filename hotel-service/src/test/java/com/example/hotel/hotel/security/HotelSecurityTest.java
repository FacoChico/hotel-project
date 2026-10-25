package com.example.hotel.hotel.security;

import com.example.hotel.hotel.testutil.JwtTestKeys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import lombok.SneakyThrows;

import static com.example.hotel.hotel.testutil.JwtTestKeys.AUDIENCE;
import static com.example.hotel.hotel.testutil.JwtTestKeys.ISSUER;
import static com.example.hotel.hotel.testutil.JwtTestKeys.KEY_PAIR;
import static com.example.hotel.hotel.testutil.JwtTestKeys.OTHER_KEY_PAIR;
import static com.example.hotel.hotel.testutil.JwtTestKeys.SERVICE_AUDIENCE;
import static com.example.hotel.hotel.testutil.JwtTestKeys.sign;
import static com.nimbusds.jose.JWSAlgorithm.RS256;
import static com.nimbusds.jose.JWSAlgorithm.RS512;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.web.servlet.function.RouterFunctions.route;
import static org.springframework.web.servlet.function.ServerResponse.ok;

@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc(print = NONE)
@Import(HotelSecurityTest.TestRoutesConfig.class)
class HotelSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerJwtProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @ParameterizedTest
    @MethodSource("permittedUserRequests")
    @SneakyThrows
    void shouldAllowRequestWhenRoleHasPermission(String role, String method, String path) {
        final var claims = userClaims();
        claims.put("roles", List.of(role));
        final var token = sign(claims, KEY_PAIR, RS256);

        final var response = mockMvc.perform(request(HttpMethod.valueOf(method), URI.create(path))
            .header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({
        "POST, /api/v1/hotels",
        "PATCH, /api/v1/hotels/1",
        "DELETE, /api/v1/hotels/1",
        "POST, /api/v1/rooms",
        "PATCH, /api/v1/rooms/1",
        "DELETE, /api/v1/rooms/1",
        "GET, /api/v1/rooms/statistics",
        "GET, /actuator/info"
    })
    @SneakyThrows
    void shouldDenyRequestWhenUserRequiresAdminPermission(String method, String path) {
        final var token = sign(userClaims(), KEY_PAIR, RS256);

        final var response = mockMvc.perform(request(HttpMethod.valueOf(method), URI.create(path))
            .header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.instance").value(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/hotels", "/api/v1/rooms", "/actuator/info"})
    @SneakyThrows
    void shouldReturnUnauthorizedWhenUserTokenIsMissing(String path) {
        final var response = mockMvc.perform(get(path));

        response.andExpect(status().isUnauthorized())
            .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUserTokens")
    @SneakyThrows
    void shouldReturnUnauthorizedWhenUserTokenIsInvalid(String scenario, String token) {
        final var response = mockMvc.perform(get("/api/v1/hotels").header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.detail").value("Authentication is required"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms/1/confirm-availability", "/api/v1/rooms/1/release"})
    @SneakyThrows
    void shouldAllowInternalRequestWhenServiceHasReservationAuthority(String path) {
        final var token = sign(serviceClaims(), KEY_PAIR, RS256);

        final var response = mockMvc.perform(post(path).header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({
        "USER, /api/v1/rooms/1/confirm-availability",
        "ADMIN, /api/v1/rooms/1/confirm-availability",
        "USER, /api/v1/rooms/1/release",
        "ADMIN, /api/v1/rooms/1/release"
    })
    @SneakyThrows
    void shouldRejectInternalRequestWhenTokenBelongsToUser(String role, String path) {
        final var claims = userClaims();
        claims.put("roles", List.of(role));
        claims.put("scope", "reservations:write");
        final var token = sign(claims, KEY_PAIR, RS256);

        final var response = mockMvc.perform(post(path).header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/rooms/1/confirm-availability", "/api/v1/rooms/1/release"})
    @SneakyThrows
    void shouldReturnUnauthorizedWhenServiceTokenIsMissing(String path) {
        final var response = mockMvc.perform(post(path));

        response.andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidServiceTokens")
    @SneakyThrows
    void shouldReturnUnauthorizedWhenServiceTokenIsInvalid(String scenario, String token) {
        final var response = mockMvc.perform(post("/api/v1/rooms/1/confirm-availability")
            .header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isUnauthorized())
            .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @CsvSource({
        "missing, /api/v1/rooms/1/confirm-availability",
        "reservations:read, /api/v1/rooms/1/confirm-availability",
        "missing, /api/v1/rooms/1/release",
        "ROLE_ADMIN, /api/v1/rooms/1/release"
    })
    @SneakyThrows
    void shouldDenyInternalRequestWhenServiceAuthorityIsMissing(String scope, String path) {
        final var claims = serviceClaims();
        if (scope.equals("missing")) {
            claims.remove("scope");
        } else {
            claims.put("scope", scope);
        }
        claims.put("roles", List.of("ADMIN"));
        final var token = sign(claims, KEY_PAIR, RS256);

        final var response = mockMvc.perform(post(path).header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /api/v1/hotels",
        "POST, /api/v1/hotels",
        "GET, /api/v1/rooms",
        "GET, /api/v1/rooms/statistics",
        "PATCH, /api/v1/rooms/1",
        "GET, /actuator/info"
    })
    @SneakyThrows
    void shouldRejectServiceTokenWhenPublicUserTokenIsRequired(String method, String path) {
        final var claims = serviceClaims();
        claims.put("roles", List.of("USER", "ADMIN"));
        final var token = sign(claims, KEY_PAIR, RS256);

        final var response = mockMvc.perform(request(HttpMethod.valueOf(method), URI.create(path))
            .header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /unlisted",
        "PUT, /api/v1/hotels/1",
        "POST, /api/v1/rooms/statistics",
        "GET, /api/hotels/v1",
        "GET, /api/v2/hotels",
        "POST, /actuator/health"
    })
    @SneakyThrows
    void shouldDenyRequestWhenPathOrMethodIsNotAllowed(String method, String path) {
        final var claims = userClaims();
        claims.put("roles", List.of("ADMIN"));
        final var token = sign(claims, KEY_PAIR, RS256);

        final var response = mockMvc.perform(request(HttpMethod.valueOf(method), URI.create(path))
            .header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isForbidden());
    }

    @Test
    @SneakyThrows
    void shouldDenyInternalRequestWhenMethodIsNotAllowed() {
        final var token = sign(serviceClaims(), KEY_PAIR, RS256);

        final var response = mockMvc.perform(get("/api/v1/rooms/1/release")
            .header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isForbidden());
    }

    @Test
    @SneakyThrows
    void shouldIgnoreScopeWhenUserTokenRequestsAdminAuthority() {
        final var claims = userClaims();
        claims.put("scope", "ADMIN ROLE_ADMIN");
        final var token = sign(claims, KEY_PAIR, RS256);

        final var response = mockMvc.perform(get("/api/v1/rooms/statistics")
            .header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "service"})
    @SneakyThrows
    void shouldAvoidSessionWhenBearerTokenIsAccepted(String tokenKind) {
        final var internal = tokenKind.equals("service");
        final var claims = internal
            ? serviceClaims()
            : userClaims();
        final var path = internal
            ? "/api/v1/rooms/1/release"
            : "/api/v1/hotels";
        final var method = internal
            ? "POST"
            : "GET";
        final var token = sign(claims, KEY_PAIR, RS256);

        final var result = mockMvc.perform(request(HttpMethod.valueOf(method), URI.create(path))
            .header(AUTHORIZATION, "Bearer " + token)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        mockMvc.perform(request(HttpMethod.valueOf(method), URI.create(path)))
            .andExpect(status().isUnauthorized());
    }

    private static Stream<Arguments> permittedUserRequests() {
        final var readPaths = List.of("/api/v1/rooms", "/api/v1/rooms/1", "/api/v1/rooms/recommend");
        final var reads = Stream.of("USER", "ADMIN")
            .flatMap(role -> readPaths.stream().map(path -> arguments(role, "GET", path)));
        final var adminRequests = Stream.of(
            arguments("ADMIN", "POST", "/api/v1/rooms"),
            arguments("ADMIN", "PATCH", "/api/v1/rooms/1"),
            arguments("ADMIN", "DELETE", "/api/v1/rooms/1"),
            arguments("ADMIN", "GET", "/api/v1/rooms/statistics"),
            arguments("ADMIN", "GET", "/actuator/info"));
        return Stream.concat(reads, adminRequests);
    }

    private static Stream<Arguments> invalidUserTokens() {
        final var tokens = new ArrayList<Arguments>();
        for (final var claim : List.of("sub", "iss", "aud", "iat", "exp", "roles")) {
            final var claims = userClaims();
            claims.remove(claim);
            tokens.add(arguments("missing " + claim, sign(claims, KEY_PAIR, RS256)));
        }

        final var invalidClaims = Map.ofEntries(
            Map.entry("sub", "0"),
            Map.entry("iss", "other-issuer"),
            Map.entry("aud", List.of(SERVICE_AUDIENCE)),
            Map.entry("iat", Date.from(Instant.now().plusSeconds(600))),
            Map.entry("exp", Date.from(Instant.now().minusSeconds(600))),
            Map.entry("roles", List.of("OWNER")));
        invalidClaims.forEach((name, value) -> {
            final var claims = userClaims();
            claims.put(name, value);
            tokens.add(arguments("invalid " + name, sign(claims, KEY_PAIR, RS256)));
        });

        for (final var roles : List.of(List.of(), List.of(1), "USER", asList((String) null))) {
            final var claims = userClaims();
            claims.put("roles", roles);
            tokens.add(arguments("malformed roles", sign(claims, KEY_PAIR, RS256)));
        }
        for (final var subject : List.of("booking-service", "9223372036854775808", "-1")) {
            final var claims = userClaims();
            claims.put("sub", subject);
            tokens.add(arguments("invalid subject", sign(claims, KEY_PAIR, RS256)));
        }

        final var excessiveLifetime = userClaims();
        excessiveLifetime.put("exp", Date.from(Instant.now().plusSeconds(7200)));
        tokens.add(arguments("excessive lifetime", sign(excessiveLifetime, KEY_PAIR, RS256)));
        final var futureNotBefore = userClaims();
        futureNotBefore.put("nbf", Date.from(Instant.now().plusSeconds(600)));
        tokens.add(arguments("future not-before", sign(futureNotBefore, KEY_PAIR, RS256)));
        final var mixedAudience = userClaims();
        mixedAudience.put("aud", List.of(AUDIENCE, SERVICE_AUDIENCE));
        tokens.add(arguments("mixed audience", sign(mixedAudience, KEY_PAIR, RS256)));
        tokens.add(arguments("wrong key", sign(userClaims(), OTHER_KEY_PAIR, RS256)));
        tokens.add(arguments("wrong algorithm", sign(userClaims(), KEY_PAIR, RS512)));
        tokens.add(arguments("malformed token", "invalid-token"));
        return tokens.stream();
    }

    private static Stream<Arguments> invalidServiceTokens() {
        final var tokens = new ArrayList<Arguments>();
        for (final var claim : List.of("sub", "iss", "aud", "iat", "exp")) {
            final var claims = serviceClaims();
            claims.remove(claim);
            tokens.add(arguments("missing " + claim, sign(claims, KEY_PAIR, RS256)));
        }

        final var invalidClaims = Map.ofEntries(
            Map.entry("sub", "another-service"),
            Map.entry("iss", "another-issuer"),
            Map.entry("aud", List.of(AUDIENCE)),
            Map.entry("iat", Date.from(Instant.now().plusSeconds(600))),
            Map.entry("exp", Date.from(Instant.now().minusSeconds(600))),
            Map.entry("scope", List.of("reservations:write")),
            Map.entry("nbf", Date.from(Instant.now().plusSeconds(600))));
        invalidClaims.forEach((name, value) -> {
            final var claims = serviceClaims();
            claims.put(name, value);
            tokens.add(arguments("invalid " + name, sign(claims, KEY_PAIR, RS256)));
        });

        final var excessiveLifetime = serviceClaims();
        excessiveLifetime.put("exp", Date.from(Instant.now().plusSeconds(121)));
        tokens.add(arguments("excessive lifetime", sign(excessiveLifetime, KEY_PAIR, RS256)));
        final var invalidWindow = serviceClaims();
        invalidWindow.put("exp", invalidWindow.get("iat"));
        tokens.add(arguments("non-positive lifetime", sign(invalidWindow, KEY_PAIR, RS256)));
        final var mixedAudience = serviceClaims();
        mixedAudience.put("aud", List.of(AUDIENCE, SERVICE_AUDIENCE));
        tokens.add(arguments("mixed audience", sign(mixedAudience, KEY_PAIR, RS256)));
        tokens.add(arguments("wrong key", sign(serviceClaims(), OTHER_KEY_PAIR, RS256)));
        tokens.add(arguments("wrong algorithm", sign(serviceClaims(), KEY_PAIR, RS512)));
        tokens.add(arguments("malformed token", "invalid-token"));
        return tokens.stream();
    }

    private static Map<String, Object> userClaims() {
        final var now = Instant.now();
        final var claims = new LinkedHashMap<String, Object>();
        claims.put("sub", "42");
        claims.put("iss", ISSUER);
        claims.put("aud", List.of(AUDIENCE));
        claims.put("iat", Date.from(now));
        claims.put("exp", Date.from(now.plusSeconds(3600)));
        claims.put("roles", List.of("USER"));
        return claims;
    }

    private static Map<String, Object> serviceClaims() {
        final var now = Instant.now();
        final var claims = new LinkedHashMap<String, Object>();
        claims.put("sub", "booking-service");
        claims.put("iss", ISSUER);
        claims.put("aud", List.of(SERVICE_AUDIENCE));
        claims.put("iat", Date.from(now));
        claims.put("exp", Date.from(now.plusSeconds(120)));
        claims.put("scope", "reservations:write");
        return claims;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestRoutesConfig {

        @Bean
        private static RouterFunction<ServerResponse> securityTestRoutes() {
            return route()
                .GET("/api/v1/rooms", request -> ok().body("rooms"))
                .GET("/api/v1/rooms/recommend", request -> ok().body("recommendations"))
                .GET("/api/v1/rooms/statistics", request -> ok().body("statistics"))
                .GET("/api/v1/rooms/{id}", request -> ok().body("room"))
                .POST("/api/v1/rooms", request -> ok().body("room"))
                .PATCH("/api/v1/rooms/{id}", request -> ok().body("room"))
                .DELETE("/api/v1/rooms/{id}", request -> ok().body("room"))
                .POST("/api/v1/rooms/{id}/confirm-availability", request -> ok().body("reserved"))
                .POST("/api/v1/rooms/{id}/release", request -> ok().body("released"))
                .build();
        }
    }
}
