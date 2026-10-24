package com.example.hotel.booking.security;

import com.example.hotel.booking.testutil.JwtTestKeys;

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

import static com.example.hotel.booking.testutil.JwtTestKeys.AUDIENCE;
import static com.example.hotel.booking.testutil.JwtTestKeys.ISSUER;
import static com.example.hotel.booking.testutil.JwtTestKeys.KEY_PAIR;
import static com.example.hotel.booking.testutil.JwtTestKeys.OTHER_KEY_PAIR;
import static com.example.hotel.booking.testutil.JwtTestKeys.sign;
import static com.example.hotel.booking.user.UserRole.ADMIN;
import static com.example.hotel.booking.user.UserRole.USER;
import static com.example.hotel.booking.user.UserRole.valueOf;
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
@Import(BookingSecurityTests.TestRoutesConfig.class)
class BookingSecurityTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenService tokenService;

    @DynamicPropertySource
    static void registerJwtProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @ParameterizedTest
    @CsvSource({
        "USER, GET, /api/bookings", "USER, POST, /api/bookings",
        "USER, GET, /api/bookings/1", "USER, DELETE, /api/bookings/1",
        "ADMIN, GET, /api/users", "ADMIN, POST, /api/users",
        "ADMIN, GET, /api/users/1", "ADMIN, PATCH, /api/users/1", "ADMIN, DELETE, /api/users/1",
        "ADMIN, GET, /actuator/info"
    })
    @SneakyThrows
    void shouldAllowRequestWhenRoleHasPermission(String role, String method, String path) {
        var token = tokenService.issueToken(42, valueOf(role));

        var response = mockMvc.perform(request(HttpMethod.valueOf(method), URI.create(path))
            .header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({
        "USER, GET, /api/users",
        "USER, POST, /api/users",
        "USER, PATCH, /api/users/1",
        "USER, DELETE, /api/users/1",
        "USER, GET, /actuator/info",
        "ADMIN, GET, /api/bookings",
        "ADMIN, POST, /api/bookings",
        "ADMIN, DELETE, /api/bookings/1"
    })
    @SneakyThrows
    void shouldDenyRequestWhenRoleHasNoPermission(String role, String method, String path) {
        var token = tokenService.issueToken(42, valueOf(role));

        var response = mockMvc.perform(request(HttpMethod.valueOf(method), URI.create(path))
            .header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.instance").value(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users", "/api/bookings", "/actuator/info", "/api/users/auth"})
    @SneakyThrows
    void shouldReturnUnauthorizedWhenTokenIsMissing(String path) {
        var request = get(path);

        var response = mockMvc.perform(request);

        response.andExpect(status().isUnauthorized())
            .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/register", "/api/users/auth"})
    @SneakyThrows
    void shouldAllowAuthenticationWhenTokenIsMissing(String path) {
        var request = post(path);

        var response = mockMvc.perform(request);

        response.andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    @SneakyThrows
    void shouldReturnUnauthorizedWhenTokenIsInvalid(String scenario, String token) {
        var request = get("/api/bookings").header(AUTHORIZATION, "Bearer " + token);

        var response = mockMvc.perform(request);

        response.andExpect(status().isUnauthorized())
            .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
            .andExpect(jsonPath("$.detail").value("Authentication is required"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @SneakyThrows
    void shouldIgnoreScopeWhenUserTokenRequestsAdminAuthority() {
        var claims = userClaims();
        claims.put("scope", "ADMIN ROLE_ADMIN");
        var token = sign(claims, KEY_PAIR, RS256);

        var response = mockMvc.perform(get("/api/users").header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /unlisted",
        "PUT, /api/users",
        "PUT, /api/bookings",
        "POST, /actuator/health"
    })
    @SneakyThrows
    void shouldDenyRequestWhenPathOrMethodIsNotAllowed(String method, String path) {
        var token = tokenService.issueToken(42, ADMIN);

        var response = mockMvc.perform(request(HttpMethod.valueOf(method), URI.create(path))
            .header(AUTHORIZATION, "Bearer " + token));

        response.andExpect(status().isForbidden());
    }

    @Test
    @SneakyThrows
    void shouldRequireBearerTokenWhenPreviousRequestWasAuthenticated() {
        var token = tokenService.issueToken(42, USER);
        mockMvc.perform(get("/api/bookings").header(AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk());

        var response = mockMvc.perform(get("/api/bookings"));

        response.andExpect(status().isUnauthorized());
    }

    @Test
    @SneakyThrows
    void shouldAvoidSessionWhenBearerTokenIsAccepted() {
        var token = tokenService.issueToken(42, USER);

        var result = mockMvc.perform(get("/api/bookings").header(AUTHORIZATION, "Bearer " + token)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    static Stream<Arguments> invalidTokens() {
        var tokens = new ArrayList<Arguments>();

        for (var claim : List.of("sub", "iss", "aud", "iat", "exp", "roles")) {
            var claims = userClaims();
            claims.remove(claim);
            tokens.add(arguments("missing " + claim, sign(claims, KEY_PAIR, RS256)));
        }

        var invalidClaims = Map.ofEntries(
            Map.entry("sub", "0"),
            Map.entry("iss", "other-issuer"),
            Map.entry("aud", List.of("internal")),
            Map.entry("iat", Date.from(Instant.now().plusSeconds(600))),
            Map.entry("exp", Date.from(Instant.now().minusSeconds(600))),
            Map.entry("roles", List.of("OWNER"))
        );
        invalidClaims.forEach((name, value) -> {
            var claims = userClaims();
            claims.put(name, value);
            tokens.add(arguments("invalid " + name, sign(claims, KEY_PAIR, RS256)));
        });

        for (var roles : List.of(List.of(), List.of(1), "USER")) {
            var claims = userClaims();
            claims.put("roles", roles);
            tokens.add(arguments("malformed roles " + roles, sign(claims, KEY_PAIR, RS256)));
        }

        for (var subject : List.of("booking-service", "9223372036854775808", "-1")) {
            var claims = userClaims();
            claims.put("sub", subject);
            tokens.add(arguments("invalid subject " + subject, sign(claims, KEY_PAIR, RS256)));
        }

        var excessiveLifetime = userClaims();
        excessiveLifetime.put("exp", Date.from(Instant.now().plusSeconds(7200)));
        tokens.add(arguments("excessive lifetime", sign(excessiveLifetime, KEY_PAIR, RS256)));

        var futureNotBefore = userClaims();
        futureNotBefore.put("nbf", Date.from(Instant.now().plusSeconds(600)));
        tokens.add(arguments("future not-before", sign(futureNotBefore, KEY_PAIR, RS256)));

        var nullRole = userClaims();
        nullRole.put("roles", asList((String) null));
        tokens.add(arguments("null role", sign(nullRole, KEY_PAIR, RS256)));
        tokens.add(arguments("wrong key", sign(userClaims(), OTHER_KEY_PAIR, RS256)));
        tokens.add(arguments("wrong algorithm", sign(userClaims(), KEY_PAIR, RS512)));
        tokens.add(arguments("malformed token", "invalid-token"));

        return tokens.stream();
    }

    private static Map<String, Object> userClaims() {
        var now = Instant.now();
        var claims = new LinkedHashMap<String, Object>();
        claims.put("sub", "42");
        claims.put("iss", ISSUER);
        claims.put("aud", List.of(AUDIENCE));
        claims.put("iat", Date.from(now));
        claims.put("exp", Date.from(now.plusSeconds(3600)));
        claims.put("roles", List.of("USER"));
        return claims;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestRoutesConfig {

        @Bean
        RouterFunction<ServerResponse> securityTestRoutes() {
            return route()
                .GET("/api/users", request -> ok().body("admin"))
                .POST("/api/users", request -> ok().body("admin"))
                .GET("/api/users/{id}", request -> ok().body("admin"))
                .PATCH("/api/users/{id}", request -> ok().body("admin"))
                .DELETE("/api/users/{id}", request -> ok().body("admin"))
                .POST("/api/users/register", request -> ok().body("register"))
                .POST("/api/users/auth", request -> ok().body("auth"))
                .GET("/api/bookings", request -> ok().body("user"))
                .POST("/api/bookings", request -> ok().body("user"))
                .GET("/api/bookings/{id}", request -> ok().body("user"))
                .DELETE("/api/bookings/{id}", request -> ok().body("user"))
                .build();
        }
    }
}
