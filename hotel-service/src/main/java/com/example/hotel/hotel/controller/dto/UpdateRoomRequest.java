package com.example.hotel.hotel.controller.dto;

import com.fasterxml.jackson.annotation.JsonSetter;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static com.fasterxml.jackson.annotation.Nulls.FAIL;
import static lombok.AccessLevel.PRIVATE;

@Getter
@NoArgsConstructor(access = PRIVATE)
public final class UpdateRoomRequest {
    @Pattern(regexp = "(?s).*\\P{javaWhitespace}.*", message = "must not be blank")
    @Size(max = 32)
    @JsonSetter(nulls = FAIL)
    private String number;

    @JsonSetter(nulls = FAIL)
    private Boolean available;
}
