package com.example.hotel.booking.controller;

import com.example.hotel.booking.controller.dto.CreateUserRequest;
import com.example.hotel.booking.domain.User;
import com.example.hotel.booking.domain.UserRole;
import com.example.hotel.booking.repository.UserRepository;
import com.example.hotel.booking.security.JwtTokenService;
import com.example.hotel.booking.testutil.JwtTestKeys;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import lombok.SneakyThrows;
import tools.jackson.databind.json.JsonMapper;

import static com.example.hotel.booking.domain.UserRole.ADMIN;
import static com.example.hotel.booking.domain.UserRole.USER;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "security.bootstrap-admin.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:user_management_tests;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc(print = NONE)
class UserManagementTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenService tokenService;
    @Autowired
    private JwtDecoder jwtDecoder;
    @Autowired
    private JsonMapper mapper;

    @DynamicPropertySource
    private static void registerJwtProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @BeforeEach
    void beforeEach() {
        userRepository.deleteAll();
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    @SneakyThrows
    void shouldCreateUserWhenAdminProvidesValidCredentials(UserRole role) {
        final var password = randomUUID().toString();
        final var payload = Map.of("username", "hotel_guest", "password", password, "role", role.name());

        final var result = mockMvc.perform(getAdminRequest(POST, "/api/v1/users", payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.username").value("hotel_guest"))
            .andExpect(jsonPath("$.role").value(role.name()))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andReturn();
        final var persistedUser = userRepository.findByUsername("hotel_guest").orElseThrow();

        assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("id").longValue())
            .isEqualTo(persistedUser.getId());
        assertThat(persistedUser.getRole()).isEqualTo(role);
        assertThat(persistedUser.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches(password, persistedUser.getPasswordHash())).isTrue();
        assertThat(result.getResponse().getContentAsString().contains(password)).isFalse();
        assertThat(new CreateUserRequest("hotel_guest", password, role).toString().contains(password)).isFalse();
    }

    @Test
    @SneakyThrows
    void shouldReturnOrderedPagesWhenUsersExist() {
        final var first = persistUser("first_guest");
        final var second = persistUser("second_guest");
        final var third = persistUser("third_guest");

        final var firstPage = mockMvc.perform(getAdminRequest(GET, "/api/v1/users?page=0&size=2", null));
        final var secondPage = mockMvc.perform(getAdminRequest(GET, "/api/v1/users?page=1&size=2", null));

        firstPage.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].id").value(first.getId()))
            .andExpect(jsonPath("$.content[1].id").value(second.getId()))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.content[*].password").isEmpty())
            .andExpect(jsonPath("$.content[*].passwordHash").isEmpty());
        secondPage.andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(third.getId()))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    @SneakyThrows
    void shouldReturnEmptyDefaultPageWhenNoUsersExist() {
        final var request = getAdminRequest(GET, "/api/v1/users", null);

        final var result = mockMvc.perform(request);

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @SneakyThrows
    void shouldReturnUserWhenIdExists() {
        final var user = persistUser("hotel_guest");

        final var result = mockMvc.perform(getAdminRequest(GET, "/api/v1/users/" + user.getId(), null));

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(user.getId()))
            .andExpect(jsonPath("$.username").value(user.getUsername()))
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @SneakyThrows
    void shouldPreserveOtherFieldsWhenOnlyUsernameChanges() {
        final var user = persistUser("hotel_guest");

        final var result = mockMvc.perform(getAdminRequest(PATCH, "/api/v1/users/" + user.getId(),
            Map.of("username", "renamed_guest")));
        final var updated = userRepository.findById(user.getId()).orElseThrow();

        result.andExpect(status().isOk()).andExpect(jsonPath("$.username").value("renamed_guest"));
        assertThat(updated.getUsername()).isEqualTo("renamed_guest");
        assertThat(updated.getRole()).isEqualTo(USER);
        assertThat(updated.getPasswordHash().equals(user.getPasswordHash())).isTrue();
    }

    @Test
    @SneakyThrows
    void shouldPreserveCredentialsWhenOnlyRoleChanges() {
        final var user = persistUser("hotel_guest");
        final var request = getAdminRequest(PATCH, "/api/v1/users/" + user.getId(), Map.of("role", "ADMIN"));

        final var result = mockMvc.perform(request);
        final var updated = userRepository.findById(user.getId()).orElseThrow();

        result.andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        assertThat(updated.getRole()).isEqualTo(ADMIN);
        assertThat(updated.getUsername()).isEqualTo(user.getUsername());
        assertThat(updated.getPasswordHash().equals(user.getPasswordHash())).isTrue();
    }

    @Test
    @SneakyThrows
    void shouldReplacePasswordWhenOnlyPasswordChanges() {
        final var user = persistUser("hotel_guest");
        final var password = randomUUID().toString();

        mockMvc.perform(getAdminRequest(PATCH, "/api/v1/users/" + user.getId(), Map.of("password", password)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.password").doesNotExist());
        final var updated = userRepository.findById(user.getId()).orElseThrow();
        final var login = mockMvc.perform(post("/api/v1/users/auth").contentType(APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(Map.of("username", user.getUsername(), "password", password))));

        assertThat(updated.getUsername()).isEqualTo(user.getUsername());
        assertThat(updated.getRole()).isEqualTo(USER);
        assertThat(passwordEncoder.matches(password, updated.getPasswordHash())).isTrue();
        assertThat(updated.getPasswordHash().equals(user.getPasswordHash())).isFalse();
        login.andExpect(status().isOk());
    }

    @Test
    @SneakyThrows
    void shouldAuthenticateWithUpdatedRoleWhenAllUserFieldsChange() {
        final var oldPassword = randomUUID().toString();
        final var passwordHash = passwordEncoder.encode(oldPassword);
        final var user = userRepository.saveAndFlush(new User("hotel_guest", passwordHash, USER));
        final var password = randomUUID().toString();
        final var payload = Map.of("username", "renamed_admin", "password", password, "role", "ADMIN");

        mockMvc.perform(getAdminRequest(PATCH, "/api/v1/users/" + user.getId(), payload))
            .andExpect(status().isOk());
        final var login = mockMvc.perform(post("/api/v1/users/auth").contentType(APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("username", "renamed_admin", "password", password))))
            .andExpect(status().isOk()).andReturn();
        final var token = jwtDecoder.decode(mapper.readTree(login.getResponse().getContentAsString())
            .get("accessToken").stringValue());

        assertThat(token.getSubject()).isEqualTo(user.getId().toString());
        assertThat(token.getClaimAsStringList("roles")).containsExactly("ADMIN");
        mockMvc.perform(post("/api/v1/users/auth").contentType(APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("username", "renamed_admin", "password", oldPassword))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @SneakyThrows
    void shouldDeleteUserWhenIdExists() {
        final var user = persistUser("hotel_guest");

        final var result = mockMvc.perform(getAdminRequest(DELETE, "/api/v1/users/" + user.getId(), null));

        result.andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(userRepository.findById(user.getId())).isEmpty();
        mockMvc.perform(getAdminRequest(GET, "/api/v1/users/" + user.getId(), null))
            .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PATCH", "DELETE"})
    @SneakyThrows
    void shouldReturnNotFoundWhenIdDoesNotExist(String method) {
        final var payload = method.equals("PATCH")
            ? Map.of("username", "hotel_guest")
            : null;
        final var request = getAdminRequest(HttpMethod.valueOf(method), "/api/v1/users/9223372036854775807", payload);

        final var result = mockMvc.perform(request);

        result.andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        assertThat(userRepository.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "POST, /api/v1/users",
        "GET, /api/v1/users",
        "GET, /api/v1/users/1",
        "PATCH, /api/v1/users/1",
        "DELETE, /api/v1/users/1"
    })
    @SneakyThrows
    void shouldReturnUnauthorizedWhenAdminTokenIsMissing(String method, String path) {
        final var request = request(HttpMethod.valueOf(method), URI.create(path));

        final var result = mockMvc.perform(request);

        result.andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(userRepository.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "POST, /api/v1/users",
        "GET, /api/v1/users",
        "GET, /api/v1/users/1",
        "PATCH, /api/v1/users/1",
        "DELETE, /api/v1/users/1"
    })
    @SneakyThrows
    void shouldReturnForbiddenWhenCallerHasUserRole(String method, String path) {
        final var token = tokenService.issueToken(42, USER);
        final var request = request(HttpMethod.valueOf(method), URI.create(path))
            .header(AUTHORIZATION, "Bearer " + token);

        final var result = mockMvc.perform(request);

        result.andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @SneakyThrows
    void shouldReturnConflictWhenCreatedUsernameAlreadyExists() {
        final var user = persistUser("hotel_guest");
        final var payload = Map.of(
            "username", user.getUsername(), "password", randomUUID().toString(), "role", "ADMIN");

        final var result = mockMvc.perform(getAdminRequest(POST, "/api/v1/users", payload));

        result.andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"));
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(USER);
    }

    @Test
    @SneakyThrows
    void shouldRollBackAllChangesWhenUpdatedUsernameConflicts() {
        persistUser("occupied_guest");
        final var user = persistUser("hotel_guest");
        final var payload = Map.of("username", "occupied_guest", "password", randomUUID().toString(), "role", "ADMIN");

        final var result = mockMvc.perform(getAdminRequest(PATCH, "/api/v1/users/" + user.getId(), payload));
        final var unchanged = userRepository.findById(user.getId()).orElseThrow();

        result.andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"));
        assertThat(unchanged.getUsername()).isEqualTo(user.getUsername());
        assertThat(unchanged.getRole()).isEqualTo(USER);
        assertThat(unchanged.getPasswordHash().equals(user.getPasswordHash())).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCreateRequests")
    @SneakyThrows
    void shouldRejectCreationWhenFieldsAreInvalid(String scenario, Object username, Object password, Object role) {
        final var payload = new LinkedHashMap<String, Object>();
        payload.put("username", username);
        payload.put("password", password);
        payload.put("role", role);

        final var result = mockMvc.perform(getAdminRequest(POST, "/api/v1/users", payload));

        result.andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").exists());
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @SneakyThrows
    void shouldRejectUpdateWhenNoFieldsAreProvided() {
        final var user = persistUser("hotel_guest");
        final var request = getAdminRequest(PATCH, "/api/v1/users/" + user.getId(), Map.of());

        final var result = mockMvc.perform(request);
        final var unchanged = userRepository.findById(user.getId()).orElseThrow();

        result.andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.detail").value("At least one user field is required"));
        assertThat(unchanged.getUsername()).isEqualTo(user.getUsername());
        assertThat(unchanged.getRole()).isEqualTo(user.getRole());
        assertThat(unchanged.getPasswordHash().equals(user.getPasswordHash())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"username\":null}",
        "{\"password\":null}",
        "{\"role\":null}",
        "{\"username\":\"renamed_guest\",\"password\":null}",
        "{\"role\":0}",
        "{\"role\":\"OWNER\"}",
        "{\"username\":\"renamed_guest\",\"role\":\"0\"}",
        "{\"username\":\"renamed_guest\",\"role\":\"\"}",
        "{\"username\":123}",
        "{\"username\":\"a\"}",
        "{\"password\":\"short\"}",
        "{\"username\":\"renamed_guest\",\"id\":1}",
        "{\"username\":\"renamed_guest\",\"passwordHash\":\"forbidden\"}",
        "{\"username\":\"renamed_guest\",\"anyFieldProvided\":true}"
    })
    @SneakyThrows
    void shouldPreserveUserWhenPatchIsInvalid(String payload) {
        final var user = persistUser("hotel_guest");
        final var request = getAdminRequest(PATCH, "/api/v1/users/" + user.getId(), null)
            .contentType(APPLICATION_JSON).content(payload);

        final var result = mockMvc.perform(request);
        final var unchanged = userRepository.findById(user.getId()).orElseThrow();

        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").exists());
        assertThat(unchanged.getUsername()).isEqualTo(user.getUsername());
        assertThat(unchanged.getRole()).isEqualTo(USER);
        assertThat(unchanged.getPasswordHash().equals(user.getPasswordHash())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "?page=-1", "?size=0", "?size=101", "?page=-1&size=0",
        "?page=abc", "?page=2147483648", "/0", "/-1", "/abc",
        "/9223372036854775808"
    })
    @SneakyThrows
    void shouldRejectReadWhenParametersAreInvalid(String suffix) {
        final var request = getAdminRequest(GET, "/api/v1/users" + suffix, null);

        final var result = mockMvc.perform(request);

        result.andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").exists());
    }

    @Test
    @SneakyThrows
    void shouldOmitPasswordWhenValidationFails() {
        final var rejectedPassword = randomUUID().toString().repeat(3);
        final var payload = Map.of("username", "hotel_guest", "password", rejectedPassword, "role", "USER");

        final var result = mockMvc.perform(getAdminRequest(POST, "/api/v1/users", payload))
            .andExpect(status().isBadRequest()).andReturn();

        assertThat(result.getResponse().getContentAsString().contains(rejectedPassword)).isFalse();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("violations").get(0)
            .has("rejectedValue")).isFalse();
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @SneakyThrows
    void shouldReturnEmptyPageWhenOffsetExceedsUserCount() {
        persistUser("hotel_guest");
        final var request = getAdminRequest(GET, "/api/v1/users?page=2147483647&size=100", null);

        final var result = mockMvc.perform(request);

        result.andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.page").value(2147483647))
            .andExpect(jsonPath("$.size").value(100))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "passwordHash", "enabled"})
    @SneakyThrows
    void shouldRejectCreationWhenRequestContainsUnknownField(String field) {
        final var payload = new LinkedHashMap<String, Object>();
        payload.put("username", "hotel_guest");
        payload.put("password", randomUUID().toString());
        payload.put("role", "USER");
        payload.put(field, "unexpected");

        final var result = mockMvc.perform(getAdminRequest(POST, "/api/v1/users", payload));

        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        assertThat(userRepository.count()).isZero();
    }

    private User persistUser(String username) {
        return userRepository.saveAndFlush(new User(
            username, passwordEncoder.encode(randomUUID().toString()), USER));
    }

    private MockHttpServletRequestBuilder getAdminRequest(HttpMethod method, String path, Object body) {
        final var token = tokenService.issueToken(42, ADMIN);
        final var request = request(method, URI.create(path))
            .header(AUTHORIZATION, "Bearer " + token);
        if (body != null) {
            request.contentType(APPLICATION_JSON).
                content(mapper.writeValueAsBytes(body));
        }
        return request;
    }

    private static Stream<Arguments> invalidCreateRequests() {
        return Stream.of(
            arguments("missing username", null, randomUUID().toString(), "USER"),
            arguments("short username", "ab", randomUUID().toString(), "USER"),
            arguments("long username", "a".repeat(65), randomUUID().toString(), "USER"),
            arguments("invalid username", "hotel guest", randomUUID().toString(), "USER"),
            arguments("numeric username", 123, randomUUID().toString(), "USER"),
            arguments("missing password", "hotel_guest", null, "USER"),
            arguments("short password", "hotel_guest", "a".repeat(11), "USER"),
            arguments("long UTF-8 password", "hotel_guest", "é".repeat(37), "USER"),
            arguments("missing role", "hotel_guest", randomUUID().toString(), null),
            arguments("unknown role", "hotel_guest", randomUUID().toString(), "OWNER"),
            arguments("numeric role", "hotel_guest", randomUUID().toString(), 0),
            arguments("quoted numeric role", "hotel_guest", randomUUID().toString(), "0"));
    }
}
