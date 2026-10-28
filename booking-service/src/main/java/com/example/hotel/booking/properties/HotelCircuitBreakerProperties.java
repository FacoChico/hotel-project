package com.example.hotel.booking.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties("booking.hotel-circuit-breaker")
public record HotelCircuitBreakerProperties(
    @Min(1) int windowSize,
    @Min(1) int minimumCalls,
    @Min(1) @Max(100) int failureRateThreshold,
    @Min(1) long openDurationMs,
    @Min(1) int halfOpenCalls
) {
    public HotelCircuitBreakerProperties {
        if (minimumCalls > windowSize) {
            throw new IllegalArgumentException("Circuit breaker minimumCalls must not exceed windowSize");
        }
    }
}
