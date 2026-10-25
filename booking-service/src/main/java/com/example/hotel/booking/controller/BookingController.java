package com.example.hotel.booking.controller;

import com.example.hotel.booking.controller.dto.BookingResponse;
import com.example.hotel.booking.controller.dto.CreateBookingRequest;
import com.example.hotel.booking.service.BookingService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import static com.example.hotel.booking.domain.BookingStatus.PENDING;
import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.OK;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
class BookingController {
    private final BookingService bookingService;

    @PostMapping
    ResponseEntity<BookingResponse> createBooking(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody CreateBookingRequest request) {
        final var result = bookingService.createBooking(Long.parseLong(jwt.getSubject()), request);
        final var booking = result.booking();
        final var status = result.created()
            ? CREATED
            : booking.status() == PENDING ? ACCEPTED : OK;
        return ResponseEntity.status(status)
            .location(URI.create("/api/v1/bookings/" + booking.id()))
            .body(booking);
    }
}
