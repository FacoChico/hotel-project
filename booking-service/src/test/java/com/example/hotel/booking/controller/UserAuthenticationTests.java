package com.example.hotel.booking.controller;

import com.example.hotel.booking.domain.User;
import com.example.hotel.booking.domain.UserRole;
import com.example.hotel.booking.repository.UserRepository;
import com.example.hotel.booking.security.JwtTokenService;
import com.example.hotel.booking.service.UserAuthenticationService;
import com.example.hotel.booking.testutil.JwtTestKeys;
import com.example.hotel.booking.controller.dto.CredentialsRequest;
import com.example.hotel.booking.controller.dto.TokenResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import lombok.SneakyThrows;
import tools.jackson.databind.json.JsonMapper;

import static com.example.hotel.booking.domain.UserRole.USER;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:authentication_tests;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc(print = NONE)
class UserAuthenticationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserAuthenticationService authenticationService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtDecoder jwtDecoder;
    @Autowired
    private JsonMapper mapper;
    @MockitoSpyBean
    private JwtTokenService tokenService;

    @DynamicPropertySource
    static void registerJwtProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @BeforeEach
    void clearUsers() {
        userRepository.deleteAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validCredentials")
    @SneakyThrows
    void shouldRegisterUserWhenCredentialsAreValid(String scenario, String username, String password) {
        var request = credentialsRequest("/api/users/v1/register", username, password);

        var result = mockMvc.perform(request).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        var user = userRepository.findByUsername(username).orElseThrow();
        var token = jwtDecoder.decode(body.get("token").get("accessToken").stringValue());

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body.get("user").get("id").longValue()).isEqualTo(user.getId());
        assertThat(body.get("user").get("username").stringValue()).isEqualTo(username);
        assertThat(body.get("user").get("role").stringValue()).isEqualTo("USER");
        assertThat(user.getRole()).isEqualTo(USER);
        assertThat(user.getPasswordHash().equals(password)).isFalse();
        assertThat(passwordEncoder.matches(password, user.getPasswordHash())).isTrue();
        assertThat(token.getSubject()).isEqualTo(user.getId().toString());
        assertThat(token.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(Duration.between(token.getIssuedAt(), token.getExpiresAt()).getSeconds()).isEqualTo(3600);
        assertThat(body.get("token").get("tokenType").stringValue()).isEqualTo("Bearer");
        assertThat(body.get("token").get("expiresIn").longValue()).isEqualTo(3600);
        assertThat(body.get("user").has("password")).isFalse();
        assertThat(body.get("user").has("passwordHash")).isFalse();
        assertThat(result.getResponse().getContentAsString().contains(password)).isFalse();
        assertThat(result.getResponse().getContentAsString().contains(user.getPasswordHash())).isFalse();
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    @SneakyThrows
    void shouldAuthenticateUserWhenPasswordMatches(UserRole role) {
        var password = randomUUID().toString();
        var user = userRepository.saveAndFlush(new User("hotel_guest", passwordEncoder.encode(password), role));
        var request = credentialsRequest("/api/users/v1/auth", user.getUsername(), password);

        var result = mockMvc.perform(request).andReturn();
        var body = mapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class);
        var token = jwtDecoder.decode(body.accessToken());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(token.getSubject()).isEqualTo(user.getId().toString());
        assertThat(token.getClaimAsStringList("roles")).containsExactly(role.name());
        assertThat(body.tokenType()).isEqualTo("Bearer");
        assertThat(body.expiresIn()).isEqualTo(3600);
        assertThat(body.toString().contains(body.accessToken())).isFalse();
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @SneakyThrows
    void shouldAuthenticateRegisteredUserWhenRegistrationHasCompleted() {
        var password = randomUUID().toString();
        mockMvc.perform(credentialsRequest("/api/users/v1/register", "hotel_guest", password))
            .andExpect(status().isCreated());

        var result = mockMvc.perform(credentialsRequest("/api/users/v1/auth", "hotel_guest", password))
            .andExpect(status().isOk()).andReturn();
        var token = mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").stringValue();

        mockMvc.perform(get("/api/users/v1").header(AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    @Test
    @SneakyThrows
    void shouldReturnSameErrorWhenUsernameOrPasswordIsIncorrect() {
        var password = randomUUID().toString();
        userRepository.saveAndFlush(new User("hotel_guest", passwordEncoder.encode(password), USER));

        var unknownUser = mockMvc.perform(credentialsRequest("/api/users/v1/auth", "unknown_guest", password));
        var wrongPassword = mockMvc.perform(credentialsRequest("/api/users/v1/auth", "hotel_guest", randomUUID().toString()));

        for (var response : List.of(unknownUser, wrongPassword)) {
            response.andExpect(status().isUnauthorized())
                .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password"))
                .andExpect(jsonPath("$.instance").value("/api/users/v1/auth"));
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("invalidCredentials")
    @SneakyThrows
    void shouldRejectRequestWhenCredentialsAreInvalid(String path, String scenario, Object username, Object password) {
        var request = credentialsRequest(path, username, password);

        var result = mockMvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());

        assertThat(body.has("code")).isTrue();
        assertThat(body.has("stackTrace")).isFalse();
        assertThat(body.has("exception")).isFalse();
        assertThat(userRepository.count()).isZero();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("unknownFields")
    @SneakyThrows
    void shouldRejectRequestWhenJsonContainsUnknownField(String path, String field) {
        var password = randomUUID().toString();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("username", "hotel_guest");
        payload.put("password", password);
        payload.put(field, "ADMIN");

        var result = mockMvc.perform(post(path).contentType(APPLICATION_JSON).content(mapper.writeValueAsBytes(payload)));

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @SneakyThrows
    void shouldOmitRejectedValuesWhenPasswordValidationFails() {
        var rejectedPassword = randomUUID().toString().repeat(3);
        var request = credentialsRequest("/api/users/v1/register", "hotel_guest", rejectedPassword);

        var result = mockMvc.perform(request).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body.get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("violations").get(0).get("field").stringValue()).isEqualTo("password");
        assertThat(body.get("violations").get(0).has("rejectedValue")).isFalse();
        assertThat(result.getResponse().getContentAsString().contains(rejectedPassword)).isFalse();
    }

    @Test
    @SneakyThrows
    void shouldReturnConflictWhenUsernameAlreadyExists() {
        var password = randomUUID().toString();
        var user = userRepository.saveAndFlush(new User("hotel_guest", passwordEncoder.encode(password), USER));

        var result = mockMvc.perform(credentialsRequest("/api/users/v1/register", "hotel_guest", randomUUID().toString()));

        result.andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"));
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getPasswordHash().equals(user.getPasswordHash())).isTrue();
    }

    @Test
    @SneakyThrows
    void shouldPersistOneUserWhenRegistrationsAreConcurrent() {
        var password = randomUUID().toString();
        var start = new CountDownLatch(1);
        Callable<Integer> registration = () -> {
            start.await();
            return mockMvc.perform(credentialsRequest("/api/users/v1/register", "hotel_guest", password))
                .andReturn().getResponse().getStatus();
        };

        try (var executor = newFixedThreadPool(2)) {
            var first = executor.submit(registration);
            var second = executor.submit(registration);
            start.countDown();
            var statuses = List.of(first.get(10, SECONDS), second.get(10, SECONDS));

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(userRepository.count()).isEqualTo(1);
        }
    }

    @Test
    void shouldRollBackRegistrationWhenTokenIssuanceFails() {
        var credentials = new CredentialsRequest("hotel_guest", randomUUID().toString());
        doThrow(new IllegalStateException("Signing unavailable"))
            .when(tokenService).issueToken(anyLong(), any(UserRole.class));

        var exception = catchThrowable(() -> authenticationService.register(credentials));

        assertThat(exception).isInstanceOf(IllegalStateException.class);
        assertThat(userRepository.count()).isZero();
        assertThat(credentials.toString().contains(credentials.password())).isFalse();
    }

    @SneakyThrows
    private MockHttpServletRequestBuilder credentialsRequest(String path, Object username, Object password) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("username", username);
        payload.put("password", password);
        return post(path).contentType(APPLICATION_JSON).content(mapper.writeValueAsBytes(payload));
    }

    static Stream<Arguments> validCredentials() {
        return Stream.of(
            arguments("minimum lengths", "usr", "a".repeat(12)),
            arguments("maximum lengths", "u".repeat(64), "a".repeat(72)),
            arguments("allowed username characters", "Hotel_Guest.1-2", randomUUID().toString()),
            arguments("minimum UTF-8 bytes", "hotel_guest", "é".repeat(6)),
            arguments("maximum UTF-8 bytes", "hotel_guest", "é".repeat(36)),
            arguments("spaces preserved", "hotel_guest", " ".repeat(12)));
    }

    static Stream<Arguments> invalidCredentials() {
        return Stream.of("/api/users/v1/register", "/api/users/v1/auth").flatMap(path -> Stream.of(
            arguments(path, "missing username", null, randomUUID().toString()),
            arguments(path, "short username", "ab", randomUUID().toString()),
            arguments(path, "long username", "a".repeat(65), randomUUID().toString()),
            arguments(path, "username whitespace", " hotel_guest", randomUUID().toString()),
            arguments(path, "username alphabet", "гость", randomUUID().toString()),
            arguments(path, "numeric username", 123, randomUUID().toString()),
            arguments(path, "missing password", "hotel_guest", null),
            arguments(path, "short password", "hotel_guest", "a".repeat(11)),
            arguments(path, "long password", "hotel_guest", "a".repeat(73)),
            arguments(path, "short UTF-8 password", "hotel_guest", "é".repeat(5)),
            arguments(path, "long UTF-8 password", "hotel_guest", "é".repeat(37)),
            arguments(path, "numeric password", "hotel_guest", 123456789012L),
            arguments(path, "object password", "hotel_guest", Map.of("value", randomUUID().toString()))));
    }

    static Stream<Arguments> unknownFields() {
        return Stream.of("/api/users/v1/register", "/api/users/v1/auth")
            .flatMap(path -> Stream.of("role", "roles", "id", "userId", "passwordHash")
                .map(field -> arguments(path, field)));
    }
}
