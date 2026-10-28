package com.example.hotel.booking.config;

import com.example.hotel.booking.testutil.JwtTestKeys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import lombok.SneakyThrows;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "security.bootstrap-admin.enabled=false",
    "booking.recovery.enabled=false"
})
@AutoConfigureMockMvc(print = NONE)
class OpenApiConfigTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JsonMapper mapper;

    @DynamicPropertySource
    private static void registerJwtProperties(DynamicPropertyRegistry registry) {
        JwtTestKeys.registerProperties(registry);
    }

    @Test
    @SneakyThrows
    void shouldDescribeBookingContractsWhenSpecificationIsRequested() {
        final var response = mockMvc.perform(get("/v3/api-docs"));

        final var specification = mapper.readTree(response.andReturn().getResponse().getContentAsString());
        final var paths = specification.path("paths");
        final var creation = paths.path("/api/v1/bookings").path("post");
        final var cancellation = paths.path("/api/v1/bookings/{id}").path("delete");

        response.andExpect(status().isOk());
        assertThat(paths.propertyNames()).containsExactlyInAnyOrder(
            "/api/v1/users/register", "/api/v1/users/auth", "/api/v1/users", "/api/v1/users/{id}",
            "/api/v1/bookings", "/api/v1/bookings/{id}", "/api/v1/bookings/recommendations");
        for (final var path : paths.propertyNames()) {
            for (final var method : paths.path(path).propertyNames()) {
                final var responses = paths.path(path).path(method).path("responses");
                assertThat(responses.propertyNames()).as(method + " " + path)
                    .anyMatch(code -> code.startsWith("2"));
                if (responses.has("204")) {
                    assertThat(responses.path("204").has("content")).isFalse();
                }
            }
        }
        assertThat(creation.path("responses").propertyNames())
            .containsExactlyInAnyOrder("200", "201", "202", "400", "401", "403", "404", "409", "503", "504");
        assertThat(cancellation.path("responses").propertyNames())
            .containsExactlyInAnyOrder("200", "202", "400", "401", "403", "404", "503");
        final var location = creation.path("responses").path("201").path("headers").path("Location");
        assertThat(referencedDefinition(specification, location).path("schema").path("type").asString())
            .isEqualTo("string");
        assertThat(creation.path("responses").path("202").path("content").path("application/json")
            .path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/BookingResponse");
        assertThat(referencedDefinition(specification, creation.path("responses").path("409"))
            .path("content").path("application/problem+json")
            .path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/Problem");
        assertThat(creation.path("security").get(0).has("userBearer")).isTrue();
        assertThat(paths.path("/api/v1/users").path("post").path("description").asString()).contains("ADMIN");
        assertThat(paths.path("/api/v1/users/auth").path("post").path("security").isMissingNode()).isTrue();
        assertThat(paths.path("/api/v1/bookings").path("get").path("responses").path("200")
            .path("content").path("application/json").path("schema").path("$ref").asString())
            .isEqualTo("#/components/schemas/BookingPageResponse");
        assertThat(creation.path("parameters").isMissingNode()).isTrue();
        assertThat(specification.path("components").path("schemas").has("Jwt")).isFalse();
    }

    @Test
    @SneakyThrows
    void shouldDescribeConditionalFieldsWhenRequestSchemasAreGenerated() {
        final var response = mockMvc.perform(get("/v3/api-docs"));

        final var specification = mapper.readTree(response.andReturn().getResponse().getContentAsString());
        final var schemas = specification.path("components").path("schemas");
        final var booking = schemas.path("CreateBookingRequest");
        final var password = schemas.path("CredentialsRequest").path("properties").path("password");

        response.andExpect(status().isOk());
        assertThat(booking.path("required").valueStream().map(JsonNode::asString).toList())
            .containsExactlyInAnyOrder("autoSelect", "endDate", "requestId", "startDate");
        assertThat(booking.path("properties").path("roomId").path("description").asString()).containsIgnoringCase("ignored");
        assertThat(booking.path("properties").path("startDate").path("format").asString()).isEqualTo("date");
        assertThat(password.path("writeOnly").booleanValue()).isTrue();
        assertThat(password.path("description").asString()).contains("UTF-8 bytes");
        assertThat(booking.has("additionalProperties")).isTrue();
        assertThat(booking.path("additionalProperties").booleanValue()).isFalse();
        assertThat(schemas.path("UpdateUserRequest").path("minProperties").intValue()).isEqualTo(1);
        assertThat(schemas.path("UpdateUserRequest").has("required")).isFalse();
        assertThat(specification.path("components").path("securitySchemes").path("userBearer")
            .path("scheme").asString()).isEqualTo("bearer");
    }

    @ParameterizedTest
    @CsvSource({
        "/v3/api-docs.yaml, 200",
        "/v3/api-docs/swagger-config, 200",
        "/swagger-ui.html, 302",
        "/swagger-ui/index.html, 200",
        "/swagger-ui/swagger-ui.css, 200",
        "/swagger-ui/swagger-initializer.js, 200"
    })
    @SneakyThrows
    void shouldExposeDocumentationWhenNoTokenIsProvided(String path, int expectedStatus) {
        final var response = mockMvc.perform(get(path));

        response.andExpect(status().is(expectedStatus))
            .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @SneakyThrows
    void shouldRejectDocumentationWriteWhenMethodIsNotAllowed() {
        final var response = mockMvc.perform(post("/v3/api-docs"));

        response.andExpect(status().isUnauthorized());
    }

    private static JsonNode referencedDefinition(JsonNode specification, JsonNode referenceNode) {
        final var reference = referenceNode.path("$ref").asString();
        assertThat(reference).startsWith("#/components/");
        return specification.at(reference.substring(1));
    }
}
