package com.example.hotel.booking.controller.dto;

import com.example.hotel.booking.controller.validation.Utf8Size;
import com.example.hotel.booking.domain.UserRole;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY;
import static com.fasterxml.jackson.annotation.Nulls.FAIL;
import static lombok.AccessLevel.PRIVATE;

@Getter
@NoArgsConstructor(access = PRIVATE)
public final class UpdateUserRequest {
    @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}",
        message = "must contain 3 to 64 letters, digits, underscores, dots or hyphens")
    @JsonSetter(nulls = FAIL)
    private String username;

    @Utf8Size(min = 12, max = 72)
    @JsonProperty(access = WRITE_ONLY)
    @JsonSetter(nulls = FAIL)
    private String password;

    @JsonSetter(nulls = FAIL)
    private UserRole role;

    @Override
    public String toString() {
        return "UpdateUserRequest[username=" + username + ", role=" + role + "]";
    }
}
