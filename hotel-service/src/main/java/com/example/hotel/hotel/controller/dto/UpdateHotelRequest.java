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
public final class UpdateHotelRequest {
    @Pattern(regexp = "(?s).*\\P{javaWhitespace}.*", message = "must not be blank")
    @Size(max = 200)
    @JsonSetter(nulls = FAIL)
    private String name;

    @Pattern(regexp = "(?s).*\\P{javaWhitespace}.*", message = "must not be blank")
    @Size(max = 500)
    @JsonSetter(nulls = FAIL)
    private String address;
}
