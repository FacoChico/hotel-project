package com.example.hotel.hotel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

import static io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    private static OpenAPI hotelOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Hotel Service API")
                .version("v1")
                .description("""
                    Hotels, rooms, availability, recommendations, occupancy statistics, and reservations.
                    Dates use YYYY-MM-DD. Stays include startDate and exclude endDate.
                    """))
            .servers(List.of(new Server().url("/").description("Current service")))
            .components(new Components()
                .addSecuritySchemes("userBearer", new SecurityScheme()
                    .type(HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT issued by Booking Service at /api/v1/users/auth or /api/v1/users/register"))
                .addSecuritySchemes("serviceBearer", new SecurityScheme()
                    .type(HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Booking Service token with the configured service audience and scope reservations:write"))
                .addResponses("InvalidRequest", problemResponse("Invalid request body or parameters"))
                .addResponses("Unauthorized", problemResponse("Authentication is required or credentials are invalid"))
                .addResponses("Forbidden", problemResponse("Insufficient authority"))
                .addResponses("HotelNotFound", problemResponse("HOTEL_NOT_FOUND"))
                .addResponses("HotelHasRooms", problemResponse("HOTEL_HAS_ROOMS"))
                .addResponses("RoomNotFound", problemResponse("ROOM_NOT_FOUND"))
                .addResponses("RoomNumberAlreadyExists", problemResponse("ROOM_NUMBER_ALREADY_EXISTS"))
                .addResponses("RoomUpdateConflict",
                    problemResponse("ROOM_NUMBER_ALREADY_EXISTS or ROOM_HAS_FUTURE_RESERVATIONS"))
                .addResponses("RoomBusy", problemResponse("ROOM_BUSY"))
                .addResponses("RoomHasReservations", problemResponse("ROOM_HAS_RESERVATIONS"))
                .addResponses("ReservationConflict",
                    problemResponse("ROOM_UNAVAILABLE, RESERVATION_RELEASED, or IDEMPOTENCY_CONFLICT"))
                .addResponses("IdempotencyConflict", problemResponse("IDEMPOTENCY_CONFLICT"))
                .addSchemas("Problem", problemSchema()));
    }

    private static ApiResponse problemResponse(String description) {
        return new ApiResponse()
            .description(description)
            .content(new Content().addMediaType(APPLICATION_PROBLEM_JSON_VALUE,
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem"))));
    }

    private static Schema<?> problemSchema() {
        return new ObjectSchema()
            .addProperty("type", new StringSchema().format("uri"))
            .addProperty("title", new StringSchema())
            .addProperty("status", new IntegerSchema())
            .addProperty("detail", new StringSchema())
            .addProperty("instance", new StringSchema().format("uri-reference"))
            .addProperty("code", new StringSchema().description("Application error code"))
            .addProperty("violations", new ArraySchema().items(new ObjectSchema()
                .addProperty("field", new StringSchema())
                .addProperty("message", new StringSchema())));
    }
}
