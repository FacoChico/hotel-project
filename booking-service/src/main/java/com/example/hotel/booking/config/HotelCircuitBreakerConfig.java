package com.example.hotel.booking.config;

import com.example.hotel.booking.gateway.exception.HotelServiceException;
import com.example.hotel.booking.properties.HotelCircuitBreakerProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import static java.time.Duration.ofMillis;

@Slf4j
@Configuration(proxyBeanMethods = false)
class HotelCircuitBreakerConfig {

    @Bean
    private static CircuitBreaker hotelCircuitBreaker(HotelCircuitBreakerProperties properties) {
        final var config = CircuitBreakerConfig.custom()
            .slidingWindowSize(properties.windowSize())
            .minimumNumberOfCalls(properties.minimumCalls())
            .failureRateThreshold(properties.failureRateThreshold())
            .waitDurationInOpenState(ofMillis(properties.openDurationMs()))
            .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
            .ignoreException(error -> error instanceof HotelServiceException failure && failure.getStatus().is4xxClientError())
            .build();
        final var circuitBreaker = CircuitBreaker.of("hotel-service", config);
        circuitBreaker.getEventPublisher().onStateTransition(event -> log.atInfo()
            .addKeyValue("dependency", "hotel-service")
            .addKeyValue("stateTransition", event.getStateTransition())
            .log("Hotel circuit breaker state changed"));
        return circuitBreaker;
    }
}
