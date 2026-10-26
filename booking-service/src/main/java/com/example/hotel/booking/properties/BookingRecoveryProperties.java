package com.example.hotel.booking.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties("booking.recovery")
public record BookingRecoveryProperties(
    boolean enabled,
    @Min(1) @Max(100) int batchSize,
    @Min(1) @Max(3600000) long pollIntervalMs,
    @Min(1) @Max(3600000) long retryDelayMs,
    @Min(1) @Max(120000) long cycleTimeoutMs
) {
}
