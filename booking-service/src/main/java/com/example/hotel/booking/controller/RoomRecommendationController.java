package com.example.hotel.booking.controller;

import com.example.hotel.booking.controller.dto.RoomPageResponse;
import com.example.hotel.booking.service.BookingService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "userBearer")
class RoomRecommendationController {
    private final BookingService bookingService;

    @Operation(summary = "Recommend rooms for a booking",
        description = "Requires USER. Available rooms are ordered by timesBooked ascending, then ID ascending.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
        @ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthorized"),
        @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
        @ApiResponse(responseCode = "503", ref = "#/components/responses/HotelUnavailable"),
        @ApiResponse(responseCode = "504", ref = "#/components/responses/BookingTimeout")
    })
    @GetMapping("/api/v1/bookings/recommendations")
    public RoomPageResponse recommendRooms(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                           @RequestParam @DateTimeFormat(pattern = "uuuu-MM-dd") LocalDate startDate,
                                           @RequestParam @DateTimeFormat(pattern = "uuuu-MM-dd") LocalDate endDate,
                                           @RequestParam(required = false) @Positive Long hotelId,
                                           @RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return bookingService.recommendRooms(startDate, endDate, hotelId, page, size, jwt.getTokenValue());
    }
}
