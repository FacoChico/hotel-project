package com.example.hotel.booking.controller.resolver;

import com.example.hotel.booking.service.exception.BookingCancellationException;
import com.example.hotel.booking.service.exception.BookingCreationException;
import com.example.hotel.booking.service.exception.BookingIdempotencyConflictException;
import com.example.hotel.booking.service.exception.BookingNotFoundException;
import com.example.hotel.booking.service.exception.EmptyUserUpdateException;
import com.example.hotel.booking.service.exception.InvalidBookingRequestException;
import com.example.hotel.booking.service.exception.UserHasBookingsException;
import com.example.hotel.booking.service.exception.UserNotFoundException;
import com.example.hotel.booking.service.exception.UsernameAlreadyExistsException;

import org.springframework.beans.TypeMismatchException;
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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import lombok.NonNull;

import static java.util.Comparator.comparing;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestControllerAdvice
class HotelBookingExceptionResolver extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BookingCancellationException.class)
    private static ResponseEntity<ProblemDetail> handleCancellationStorageFailure(BookingCancellationException exception) {
        return ResponseEntity.status(SERVICE_UNAVAILABLE)
            .body(getProblemDetails(SERVICE_UNAVAILABLE, "BOOKING_STORAGE_UNAVAILABLE", exception.getMessage()));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    private static ResponseEntity<ProblemDetail> handleMissingBooking(BookingNotFoundException exception) {
        return ResponseEntity.status(NOT_FOUND)
            .body(getProblemDetails(NOT_FOUND, "BOOKING_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(BookingCreationException.class)
    private static ResponseEntity<ProblemDetail> handleBookingFailure(BookingCreationException exception) {
        final var problem = getProblemDetails(exception.getStatus(), exception.getCode(), exception.getMessage());
        problem.setProperty("bookingId", exception.getBookingId());
        problem.setProperty("releasePending", exception.isReleasePending());
        return ResponseEntity.status(exception.getStatus()).body(problem);
    }

    @ExceptionHandler(BookingIdempotencyConflictException.class)
    private static ResponseEntity<ProblemDetail> handleBookingConflict(BookingIdempotencyConflictException exception) {
        final var problem = getProblemDetails(CONFLICT, "IDEMPOTENCY_CONFLICT", exception.getMessage());
        problem.setProperty("bookingId", exception.getBookingId());
        return ResponseEntity.status(CONFLICT).body(problem);
    }

    @ExceptionHandler(InvalidBookingRequestException.class)
    private static ResponseEntity<ProblemDetail> handleInvalidBooking(InvalidBookingRequestException exception) {
        return ResponseEntity.badRequest()
            .body(getProblemDetails(BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage()));
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    private static ResponseEntity<ProblemDetail> handleUsernameConflict(UsernameAlreadyExistsException exception) {
        return ResponseEntity.status(CONFLICT)
            .body(getProblemDetails(CONFLICT, "USERNAME_ALREADY_EXISTS", exception.getMessage()));
    }

    @ExceptionHandler(UserHasBookingsException.class)
    private static ResponseEntity<ProblemDetail> handleUserBookingHistory(UserHasBookingsException exception) {
        return ResponseEntity.status(CONFLICT)
            .body(getProblemDetails(CONFLICT, "USER_HAS_BOOKINGS", exception.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    private static ResponseEntity<ProblemDetail> handleInvalidCredentials() {
        return ResponseEntity.status(UNAUTHORIZED)
            .header(WWW_AUTHENTICATE, "Bearer")
            .body(getProblemDetails(UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password"));
    }

    @ExceptionHandler(EmptyUserUpdateException.class)
    private static ResponseEntity<ProblemDetail> handleEmptyUserUpdate(EmptyUserUpdateException exception) {
        return ResponseEntity.badRequest()
            .body(getProblemDetails(BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    private static ResponseEntity<ProblemDetail> handleMissingUser(UserNotFoundException exception) {
        return ResponseEntity.status(NOT_FOUND)
            .body(getProblemDetails(NOT_FOUND, "USER_NOT_FOUND", exception.getMessage()));
    }

    @Override
    protected @NonNull ResponseEntity<Object> handleHandlerMethodValidationException(@NonNull HandlerMethodValidationException exception,
                                                                                     @NonNull HttpHeaders headers,
                                                                                     @NonNull HttpStatusCode status,
                                                                                     @NonNull WebRequest request) {
        final var problem = getProblemDetails(BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
        final var violations = exception.getParameterValidationResults().stream()
            .flatMap(result -> result.getResolvableErrors().stream()
                .map(error -> new Violation(result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
            .sorted(comparing(Violation::field))
            .toList();
        problem.setProperty("violations", violations);

        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected @NonNull ResponseEntity<Object> handleTypeMismatch(@NonNull TypeMismatchException exception,
                                                                 @NonNull HttpHeaders headers,
                                                                 @NonNull HttpStatusCode status,
                                                                 @NonNull WebRequest request) {
        return ResponseEntity.badRequest()
            .body(getProblemDetails(BAD_REQUEST, "INVALID_REQUEST_PARAMETERS", "Request parameters have invalid types"));
    }

    @Override
    protected @NonNull ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException exception,
                                                                           @NonNull HttpHeaders headers,
                                                                           @NonNull HttpStatusCode status,
                                                                           @NonNull WebRequest request) {
        final var details = getProblemDetails(BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
        final var violations = exception.getBindingResult().getFieldErrors().stream()
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

    private static ProblemDetail getProblemDetails(HttpStatus status, String code, String detail) {
        final var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }

    private record Violation(
        String field,
        String message
    ) {
    }
}
