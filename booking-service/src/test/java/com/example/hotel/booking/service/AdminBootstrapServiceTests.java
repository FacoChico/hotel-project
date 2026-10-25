package com.example.hotel.booking.service;

import com.example.hotel.booking.domain.User;
import com.example.hotel.booking.properties.AdminBootstrapProperties;
import com.example.hotel.booking.repository.UserRepository;
import com.example.hotel.booking.testutil.JwtTestKeys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.stream.Stream;

import lombok.SneakyThrows;
import tools.jackson.databind.json.JsonMapper;

import static com.example.hotel.booking.domain.UserRole.ADMIN;
import static com.example.hotel.booking.domain.UserRole.USER;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:admin_bootstrap_tests;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "security.bootstrap-admin.username=hotel_admin"
})
@AutoConfigureMockMvc(print = NONE)
class AdminBootstrapServiceTests {
    private static final String BOOTSTRAP_PASSWORD = randomUUID().toString();

    @Autowired
    private AdminBootstrapService bootstrapService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JsonMapper mapper;
    @Autowired
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    private static void registerProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
        registry.add("security.bootstrap-admin.password", () -> BOOTSTRAP_PASSWORD);
    }

    @Test
    void shouldCreateAdminWhenApplicationStartsWithBootstrapEnabled() {
        final var user = userRepository.findByUsername("hotel_admin").orElseThrow();

        final var passwordMatches = passwordEncoder.matches(BOOTSTRAP_PASSWORD, user.getPasswordHash());

        assertThat(user.getId()).isPositive();
        assertThat(user.getRole()).isEqualTo(ADMIN);
        assertThat(user.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(user.getPasswordHash().equals(BOOTSTRAP_PASSWORD)).isFalse();
        assertThat(passwordMatches).isTrue();
    }

    @Test
    @SneakyThrows
    void shouldAuthenticateAdminWhenBootstrapCredentialsAreProvided() {
        final var user = userRepository.findByUsername("hotel_admin").orElseThrow();
        final var payload = mapper.writeValueAsBytes(Map.of("username", "hotel_admin", "password", BOOTSTRAP_PASSWORD));

        final var result = mockMvc.perform(post("/api/v1/users/auth").contentType(APPLICATION_JSON).content(payload))
            .andExpect(status().isOk())
            .andReturn();
        final var accessToken = mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").stringValue();
        final var token = jwtDecoder.decode(accessToken);

        assertThat(token.getClaimAsStringList("roles")).containsExactly("ADMIN");
        assertThat(token.getSubject()).isEqualTo(user.getId().toString());
        assertThat(result.getResponse().getContentAsString().contains(BOOTSTRAP_PASSWORD)).isFalse();
        mockMvc.perform(get("/actuator/info").header(AUTHORIZATION, "Bearer " + accessToken))
            .andExpect(status().isOk());
    }

    @Test
    void shouldPreserveAdminWhenInitializationRepeatsWithAnotherPassword() {
        final var originalUser = userRepository.findByUsername("hotel_admin").orElseThrow();
        final var replacementPassword = randomUUID().toString();
        final var properties = new AdminBootstrapProperties(true, "hotel_admin", replacementPassword);

        bootstrapService.initializeAdmin(properties);
        final var persistedUser = userRepository.findByUsername("hotel_admin").orElseThrow();

        assertThat(persistedUser.getId()).isEqualTo(originalUser.getId());
        assertThat(persistedUser.getRole()).isEqualTo(ADMIN);
        assertThat(persistedUser.getPasswordHash().equals(originalUser.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(BOOTSTRAP_PASSWORD, persistedUser.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(replacementPassword, persistedUser.getPasswordHash())).isFalse();
    }

    @Test
    void shouldRejectInitializationWhenUsernameBelongsToRegularUser() {
        final var passwordHash = passwordEncoder.encode(randomUUID().toString());
        final var existingUser = userRepository.saveAndFlush(new User("existing_guest", passwordHash, USER));
        final var userCount = userRepository.count();
        final var properties = new AdminBootstrapProperties(true, existingUser.getUsername(), randomUUID().toString());

        final var exception = catchThrowable(() -> bootstrapService.initializeAdmin(properties));
        final var persistedUser = userRepository.findById(existingUser.getId()).orElseThrow();

        assertThat(exception).isInstanceOf(IllegalStateException.class)
            .hasMessage("Bootstrap Admin username is already assigned to a non-admin user");
        assertThat(persistedUser.getRole()).isEqualTo(USER);
        assertThat(persistedUser.getPasswordHash().equals(existingUser.getPasswordHash())).isTrue();
        assertThat(userRepository.count()).isEqualTo(userCount);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCredentials")
    void shouldRejectInitializationWhenCredentialsAreInvalid(String scenario,
                                                             String username,
                                                             String password,
                                                             String field) {
        final var properties = new AdminBootstrapProperties(true, username, password);
        final var userCount = userRepository.count();

        final var exception = catchThrowable(() -> bootstrapService.initializeAdmin(properties));

        assertThat(exception).isInstanceOf(IllegalStateException.class)
            .hasMessage("Invalid bootstrap admin credentials: " + field)
            .hasNoCause();
        assertThat(userRepository.count()).isEqualTo(userCount);
        if (password != null && !password.isEmpty()) {
            assertThat(exception.getMessage().contains(password)).isFalse();
            assertThat(properties.toString().contains(password)).isFalse();
        }
    }

    private static Stream<Arguments> invalidCredentials() {
        return Stream.of(
            arguments("missing credentials", null, null, "password, username"),
            arguments("missing username", null, randomUUID().toString(), "username"),
            arguments("blank username", "", randomUUID().toString(), "username"),
            arguments("invalid username", "admin user", randomUUID().toString(), "username"),
            arguments("missing password", "another_admin", null, "password"),
            arguments("blank password", "another_admin", "", "password"),
            arguments("short password", "another_admin", "a".repeat(11), "password"),
            arguments("long password", "another_admin", randomUUID().toString().repeat(3), "password"),
            arguments("long UTF-8 password", "another_admin", "é".repeat(37), "password"));
    }
}
