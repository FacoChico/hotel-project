package com.example.hotel.hotel.controller.dto;

import com.fasterxml.jackson.annotation.JsonSetter;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static com.fasterxml.jackson.annotation.Nulls.FAIL;
import static io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE;
import static lombok.AccessLevel.PRIVATE;

@Schema(additionalProperties = FALSE, minProperties = 1)
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
