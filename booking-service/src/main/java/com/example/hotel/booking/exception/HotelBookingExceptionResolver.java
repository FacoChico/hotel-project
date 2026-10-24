package com.example.hotel.booking.exception;

import com.example.hotel.booking.service.exception.UsernameAlreadyExistsException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import lombok.NonNull;

import static java.util.Comparator.comparing;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestControllerAdvice
class HotelBookingExceptionResolver extends ResponseEntityExceptionHandler {

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    ResponseEntity<ProblemDetail> handleUsernameConflict(UsernameAlreadyExistsException exception) {
        return ResponseEntity.status(CONFLICT)
            .body(getProblemDetails(CONFLICT, "USERNAME_ALREADY_EXISTS", exception.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ProblemDetail> handleInvalidCredentials() {
        return ResponseEntity.status(UNAUTHORIZED)
            .header(WWW_AUTHENTICATE, "Bearer")
            .body(getProblemDetails(UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password"));
    }

    @Override
    protected @NonNull ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException exception,
                                                                           @NonNull HttpHeaders headers,
                                                                           @NonNull HttpStatusCode status,
                                                                           @NonNull WebRequest request) {
        var details = getProblemDetails(BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
        var violations = exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new Violation(error.getField(), error.getDefaultMessage()))
            .sorted(comparing(Violation::field))
            .toList();
        details.setProperty("violations", violations);

        return ResponseEntity.badRequest().body(details);
    }

    @Override
    protected @NonNull ResponseEntity<Object> handleHttpMessageNotReadable(@NonNull HttpMessageNotReadableException exception,
                                                                           @NonNull HttpHeaders headers,
                                                                           @NonNull HttpStatusCode status,
                                                                           @NonNull WebRequest request) {
        return ResponseEntity.badRequest()
            .body(getProblemDetails(BAD_REQUEST, "INVALID_REQUEST_BODY",
                "Request body must contain valid JSON with supported fields"));
    }

    private ProblemDetail getProblemDetails(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }

    private record Violation(
        String field,
        String message
    ) {
    }
}
