package com.example.hotel.booking.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties("security.service-jwt")
public record ServiceJwtProperties(
    @NotBlank String audience
) {
}
