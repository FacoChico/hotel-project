package com.example.hotel.booking.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties("booking.hotel-client")
public record HotelClientProperties(
    @Min(1) @Max(60000) long connectTimeoutMs,
    @Min(1) @Max(60000) long readTimeoutMs,
    @Min(1) @Max(5) int maxAttempts,
    @Min(1) @Max(1000) long backoffMs,
    @Min(1) @Max(120000) long operationTimeoutMs
) {
}
