package com.example.hotel.gateway.controller.resolver;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@RestControllerAdvice
class GatewayExceptionResolver {

    @ExceptionHandler({HttpServerErrorException.class, ResourceAccessException.class})
    private static ResponseEntity<ProblemDetail> handleUnavailableService() {
        final var problem = ProblemDetail.forStatusAndDetail(SERVICE_UNAVAILABLE, "Service is unavailable");
        problem.setProperty("code", "SERVICE_UNAVAILABLE");
        return ResponseEntity.status(SERVICE_UNAVAILABLE).body(problem);
    }
}
