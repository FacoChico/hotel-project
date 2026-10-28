package com.example.hotel.hotel.controller;

import com.example.hotel.hotel.controller.dto.ConfirmAvailabilityRequest;
import com.example.hotel.hotel.controller.dto.ReleaseReservationRequest;
import com.example.hotel.hotel.controller.dto.ReservationResponse;
import com.example.hotel.hotel.service.RoomReservationService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Internal reservations")
@SecurityRequirement(name = "serviceBearer")
@ApiResponses({
    @ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthorized"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
    @ApiResponse(responseCode = "404", ref = "#/components/responses/RoomNotFound"),
    @ApiResponse(responseCode = "503", ref = "#/components/responses/RoomBusy")
})
@PreAuthorize("hasAuthority('reservations:write')")
@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
class RoomReservationController {
    private final RoomReservationService reservationService;

    @Operation(summary = "Confirm room availability",
        description = """
            Requires a Booking Service JWT with scope reservations:write. The interval is [startDate, endDate).
            Repeating the same bookingId, requestId, and dates does not create another reservation.
            RELEASED reservations cannot be confirmed.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "409", ref = "#/components/responses/ReservationConflict")
    })
    @PostMapping("/{id:[0-9]+}/confirm-availability")
    public ReservationResponse confirmAvailability(@PathVariable @Positive long id,
                                                   @Valid @RequestBody ConfirmAvailabilityRequest request) {
        return reservationService.confirmAvailability(id, request);
    }

    @Operation(summary = "Release a room reservation",
        description = """
            Requires a Booking Service JWT. Repeating the same bookingId and requestId does not release twice.
            COMPENSATION decreases timesBooked when releasing RESERVED. USER_CANCELLATION leaves it unchanged.
            Release before confirmation records RELEASED and rejects later confirmation.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "409", ref = "#/components/responses/IdempotencyConflict")
    })
    @PostMapping("/{id:[0-9]+}/release")
    public ReservationResponse releaseReservation(@PathVariable @Positive long id,
                                                  @Valid @RequestBody ReleaseReservationRequest request) {
        return reservationService.releaseReservation(id, request);
    }
}
