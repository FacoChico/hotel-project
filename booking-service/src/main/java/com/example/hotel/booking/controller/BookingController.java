package com.example.hotel.booking.controller;

import com.example.hotel.booking.controller.dto.BookingPageResponse;
import com.example.hotel.booking.controller.dto.BookingResponse;
import com.example.hotel.booking.controller.dto.CreateBookingRequest;
import com.example.hotel.booking.service.BookingService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

import static com.example.hotel.booking.domain.BookingStatus.PENDING;
import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.OK;

@Tag(name = "Bookings")
@SecurityRequirement(name = "userBearer")
@ApiResponses({
    @ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthorized"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden")
})
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
class BookingController {
    private final BookingService bookingService;

    @Operation(summary = "Create or repeat a booking",
        description = """
            Requires USER. requestId is unique per user. Repeating the same request returns the existing booking.
            Automatic selection refreshes candidates after conflicts within the operation deadline.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            description = "Booking confirmed",
            useReturnTypeSchema = true,
            headers = @Header(name = "Location", ref = "#/components/headers/BookingLocation")),
        @ApiResponse(responseCode = "200",
            description = "Existing CONFIRMED or CANCELLED booking",
            useReturnTypeSchema = true,
            headers = @Header(name = "Location", ref = "#/components/headers/BookingLocation")),
        @ApiResponse(responseCode = "202",
            description = "Existing PENDING booking",
            useReturnTypeSchema = true,
            headers = @Header(name = "Location", ref = "#/components/headers/BookingLocation")),
        @ApiResponse(responseCode = "404", ref = "#/components/responses/BookingTargetNotFound"),
        @ApiResponse(responseCode = "409", ref = "#/components/responses/BookingCreationConflict"),
        @ApiResponse(responseCode = "503", ref = "#/components/responses/BookingCreationUnavailable"),
        @ApiResponse(responseCode = "504", ref = "#/components/responses/BookingTimeout")
    })
    @PostMapping
    ResponseEntity<BookingResponse> createBooking(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody CreateBookingRequest request) {
        final var result = bookingService.createBooking(Long.parseLong(jwt.getSubject()), request, jwt.getTokenValue());
        final var booking = result.booking();
        final var status = result.created()
            ? CREATED
            : booking.status() == PENDING
                ? ACCEPTED
                : OK;
        return ResponseEntity.status(status)
            .location(URI.create("/api/v1/bookings/" + booking.id()))
            .body(booking);
    }

    @Operation(summary = "List personal bookings",
        description = "Requires USER. Returns the user's bookings in all statuses, ordered by createdAt descending and ID ascending.")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @GetMapping
    private BookingPageResponse listBookings(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return bookingService.listBookings(Long.parseLong(jwt.getSubject()), page, size);
    }

    @Operation(summary = "Get a personal booking",
        description = "Requires USER. Missing bookings and bookings owned by another user return 404.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "404", ref = "#/components/responses/BookingNotFound")
    })
    @GetMapping("/{id:[0-9]+}")
    private BookingResponse getBooking(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id) {
        return bookingService.getBooking(Long.parseLong(jwt.getSubject()), id);
    }

    @Operation(summary = "Cancel a personal booking",
        description = """
            Requires USER. The cancelled booking remains in the user's history.
            Repeated cancellation returns the current state. releasePending indicates whether room release is pending.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "CANCELLED with releasePending=false",
            useReturnTypeSchema = true,
            headers = @Header(name = "Location", ref = "#/components/headers/BookingLocation")),
        @ApiResponse(responseCode = "202",
            description = "CANCELLED with releasePending=true",
            useReturnTypeSchema = true,
            headers = @Header(name = "Location", ref = "#/components/headers/BookingLocation")),
        @ApiResponse(responseCode = "404", ref = "#/components/responses/BookingNotFound"),
        @ApiResponse(responseCode = "503", ref = "#/components/responses/BookingCancellationUnavailable")
    })
    @DeleteMapping("/{id:[0-9]+}")
    private ResponseEntity<BookingResponse> cancelBooking(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                                          @PathVariable @Positive long id) {
        final var booking = bookingService.cancelBooking(Long.parseLong(jwt.getSubject()), id);
        final var status = booking.releasePending()
            ? ACCEPTED
            : OK;
        return ResponseEntity.status(status)
            .location(URI.create("/api/v1/bookings/" + booking.id()))
            .body(booking);
    }
}
