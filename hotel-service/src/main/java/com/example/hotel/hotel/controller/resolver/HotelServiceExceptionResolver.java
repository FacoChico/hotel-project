package com.example.hotel.hotel.controller.resolver;

import com.example.hotel.hotel.service.exception.EmptyHotelUpdateException;
import com.example.hotel.hotel.service.exception.EmptyRoomUpdateException;
import com.example.hotel.hotel.service.exception.HotelHasRoomsException;
import com.example.hotel.hotel.service.exception.HotelNotFoundException;
import com.example.hotel.hotel.service.exception.RoomNotFoundException;
import com.example.hotel.hotel.service.exception.RoomNumberAlreadyExistsException;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import lombok.NonNull;

import static java.util.Comparator.comparing;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestControllerAdvice
class HotelServiceExceptionResolver extends ResponseEntityExceptionHandler {

    @ExceptionHandler(HotelHasRoomsException.class)
    private static ResponseEntity<ProblemDetail> handleHotelWithRooms(HotelHasRoomsException exception) {
        return ResponseEntity.status(CONFLICT)
            .body(getProblemDetails(CONFLICT, "HOTEL_HAS_ROOMS", exception.getMessage()));
    }

    @ExceptionHandler(EmptyHotelUpdateException.class)
    private static ResponseEntity<ProblemDetail> handleEmptyHotelUpdate(EmptyHotelUpdateException exception) {
        return ResponseEntity.badRequest()
            .body(getProblemDetails(BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage()));
    }

    @ExceptionHandler(HotelNotFoundException.class)
    private static ResponseEntity<ProblemDetail> handleMissingHotel(HotelNotFoundException exception) {
        return ResponseEntity.status(NOT_FOUND)
            .body(getProblemDetails(NOT_FOUND, "HOTEL_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(RoomNumberAlreadyExistsException.class)
    private static ResponseEntity<ProblemDetail> handleRoomNumberConflict(RoomNumberAlreadyExistsException exception) {
        return ResponseEntity.status(CONFLICT)
            .body(getProblemDetails(CONFLICT, "ROOM_NUMBER_ALREADY_EXISTS", exception.getMessage()));
    }

    @ExceptionHandler(RoomNotFoundException.class)
    private static ResponseEntity<ProblemDetail> handleMissingRoom(RoomNotFoundException exception) {
        return ResponseEntity.status(NOT_FOUND)
            .body(getProblemDetails(NOT_FOUND, "ROOM_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(EmptyRoomUpdateException.class)
    private static ResponseEntity<ProblemDetail> handleEmptyRoomUpdate(EmptyRoomUpdateException exception) {
        return ResponseEntity.badRequest()
            .body(getProblemDetails(BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage()));
    }

    @Override
    protected @NonNull ResponseEntity<Object> handleHttpRequestMethodNotSupported(@NonNull HttpRequestMethodNotSupportedException exception,
                                                                                  @NonNull HttpHeaders headers,
                                                                                  @NonNull HttpStatusCode status,
                                                                                  @NonNull WebRequest request) {
        return ResponseEntity.status(METHOD_NOT_ALLOWED)
            .headers(headers)
            .body(getProblemDetails(METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Request method is not supported"));
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
