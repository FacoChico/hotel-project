package com.example.hotel.booking.controller.dto;

import com.example.hotel.booking.controller.validation.Utf8Size;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import static com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY;

public record CredentialsRequest(
    @NotNull
    @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}",
        message = "must contain 3 to 64 letters, digits, underscores, dots or hyphens")
    String username,

    @NotNull
    @Utf8Size(min = 12, max = 72)
    @JsonProperty(access = WRITE_ONLY)
    String password
) {

    @Override
    public String toString() {
        return "CredentialsRequest[username=" + username + "]";
    }
}
