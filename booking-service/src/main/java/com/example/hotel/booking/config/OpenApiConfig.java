package com.example.hotel.booking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
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
    private static OpenAPI bookingOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Booking Service API")
                .version("v1")
                .description("""
                    User registration, authentication, user management, and bookings.
                    Dates use YYYY-MM-DD. Stays include startDate and exclude endDate.
                    """))
            .servers(List.of(new Server().url("/").description("Current service")))
            .components(new Components()
                .addSecuritySchemes("userBearer", new SecurityScheme()
                    .type(HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT issued by Booking Service at /api/v1/users/auth or /api/v1/users/register"))
                .addResponses("InvalidRequest", problemResponse("Invalid request body or parameters"))
                .addResponses("Unauthorized", problemResponse("Authentication is required or credentials are invalid"))
                .addResponses("Forbidden", problemResponse("Insufficient authority"))
                .addResponses("UsernameAlreadyExists", problemResponse("USERNAME_ALREADY_EXISTS"))
                .addResponses("UserNotFound", problemResponse("USER_NOT_FOUND"))
                .addResponses("UserHasBookings", problemResponse("USER_HAS_BOOKINGS"))
                .addResponses("BookingNotFound", problemResponse("BOOKING_NOT_FOUND"))
                .addResponses("BookingTargetNotFound", problemResponse("USER_NOT_FOUND or ROOM_NOT_FOUND"))
                .addResponses("BookingCreationConflict",
                    problemResponse("IDEMPOTENCY_CONFLICT, ROOM_UNAVAILABLE, or BOOKING_CANCELLED"))
                .addResponses("BookingCreationUnavailable",
                    problemResponse("HOTEL_SERVICE_UNAVAILABLE or BOOKING_STORAGE_UNAVAILABLE"))
                .addResponses("BookingTimeout", problemResponse("BOOKING_TIMEOUT"))
                .addResponses("BookingCancellationUnavailable",
                    problemResponse("BOOKING_STORAGE_UNAVAILABLE"))
                .addHeaders("BookingLocation", new Header()
                    .description("Booking resource path")
                    .schema(new StringSchema()))
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
            .addProperty("bookingId", new IntegerSchema().format("int64")
                .description("ID of the booking associated with the error"))
            .addProperty("releasePending", new BooleanSchema()
                .description("Whether room release is still pending"))
            .addProperty("violations", new ArraySchema().items(new ObjectSchema()
                .addProperty("field", new StringSchema())
                .addProperty("message", new StringSchema())));
    }
}
