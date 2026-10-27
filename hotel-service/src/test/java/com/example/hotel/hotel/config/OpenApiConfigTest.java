package com.example.hotel.hotel.config;

import com.example.hotel.hotel.testutil.JwtTestKeys;

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

@SpringBootTest(properties = "eureka.client.enabled=false")
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
    void shouldDescribeHotelContractsWhenSpecificationIsRequested() {
        final var response = mockMvc.perform(get("/v3/api-docs"));

        final var specification = mapper.readTree(response.andReturn().getResponse().getContentAsString());
        final var paths = specification.path("paths");
        final var confirmation = paths.path("/api/v1/rooms/{id}/confirm-availability").path("post");
        final var release = paths.path("/api/v1/rooms/{id}/release").path("post");
        final var recommendation = paths.path("/api/v1/rooms/recommend").path("get");

        response.andExpect(status().isOk());
        assertThat(paths.propertyNames()).containsExactlyInAnyOrder(
            "/api/v1/hotels", "/api/v1/hotels/{id}", "/api/v1/rooms", "/api/v1/rooms/{id}",
            "/api/v1/rooms/recommend", "/api/v1/rooms/statistics",
            "/api/v1/rooms/{id}/confirm-availability", "/api/v1/rooms/{id}/release");
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
        assertThat(confirmation.path("security").get(0).has("serviceBearer")).isTrue();
        assertThat(release.path("security").get(0).has("serviceBearer")).isTrue();
        assertThat(recommendation.path("security").get(0).has("userBearer")).isTrue();
        assertThat(confirmation.path("responses").propertyNames())
            .containsExactlyInAnyOrder("200", "400", "401", "403", "404", "409", "503");
        assertThat(referencedDefinition(specification, confirmation.path("responses").path("409"))
            .path("content").path("application/problem+json")
            .path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/Problem");
        assertThat(paths.path("/api/v1/hotels").path("post").path("responses").has("201")).isTrue();
        assertThat(paths.path("/api/v1/rooms/{id}").path("delete").path("responses").has("204")).isTrue();
        assertThat(paths.path("/api/v1/rooms/statistics").path("get").path("description").asString()).contains("ADMIN");
        assertThat(recommendation.path("responses").path("200").path("content").path("application/json")
            .path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/RoomPageResponse");
        assertThat(recommendation.path("description").asString()).contains("timesBooked");
    }

    @Test
    @SneakyThrows
    void shouldDescribeSchemasWhenHotelSpecificationIsGenerated() {
        final var response = mockMvc.perform(get("/v3/api-docs"));

        final var specification = mapper.readTree(response.andReturn().getResponse().getContentAsString());
        final var components = specification.path("components");
        final var schemas = components.path("schemas");

        response.andExpect(status().isOk());
        assertThat(schemas.path("ConfirmAvailabilityRequest").path("properties").path("startDate")
            .path("format").asString()).isEqualTo("date");
        assertThat(schemas.path("RoomResponse").path("properties").path("id").path("format").asString())
            .isEqualTo("int64");
        assertThat(schemas.path("CreateRoomRequest").has("additionalProperties")).isTrue();
        assertThat(schemas.path("CreateRoomRequest").path("additionalProperties").booleanValue()).isFalse();
        assertThat(schemas.path("UpdateRoomRequest").path("minProperties").intValue()).isEqualTo(1);
        assertThat(schemas.path("UpdateRoomRequest").has("required")).isFalse();
        assertThat(schemas.path("UpdateHotelRequest").has("required")).isFalse();
        assertThat(components.path("securitySchemes").path("serviceBearer").path("scheme").asString())
            .isEqualTo("bearer");
        assertThat(components.path("securitySchemes").path("userBearer").path("scheme").asString())
            .isEqualTo("bearer");
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
