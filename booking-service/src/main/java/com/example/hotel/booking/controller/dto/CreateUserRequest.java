package com.example.hotel.booking.controller.dto;

import com.example.hotel.booking.controller.validation.Utf8Size;
import com.example.hotel.booking.domain.UserRole;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import static com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY;
import static io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE;

@Schema(additionalProperties = FALSE)
public record CreateUserRequest(
    @NotNull
    @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}",
        message = "must contain 3 to 64 letters, digits, underscores, dots or hyphens")
    String username,

    @NotNull
    @Schema(format = "password", description = "12 to 72 UTF-8 bytes, without trimming or normalization")
    @Utf8Size(min = 12, max = 72)
    @JsonProperty(access = WRITE_ONLY)
    String password,

    @NotNull UserRole role
) {

    @Override
    public String toString() {
        return "CreateUserRequest[username=" + username + ", role=" + role + "]";
    }
}
