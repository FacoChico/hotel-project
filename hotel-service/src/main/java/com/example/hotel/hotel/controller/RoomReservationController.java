package com.example.hotel.hotel.controller;

import com.example.hotel.hotel.controller.dto.ConfirmAvailabilityRequest;
import com.example.hotel.hotel.controller.dto.ReleaseReservationRequest;
import com.example.hotel.hotel.controller.dto.ReservationResponse;
import com.example.hotel.hotel.service.RoomReservationService;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
class RoomReservationController {
    private final RoomReservationService reservationService;

    @PostMapping("/{id:[0-9]+}/confirm-availability")
    private ReservationResponse confirmAvailability(@PathVariable @Positive long id,
                                                    @Valid @RequestBody ConfirmAvailabilityRequest request) {
        return reservationService.confirmAvailability(id, request);
    }

    @PostMapping("/{id:[0-9]+}/release")
    private ReservationResponse releaseReservation(@PathVariable @Positive long id,
                                                   @Valid @RequestBody ReleaseReservationRequest request) {
        return reservationService.releaseReservation(id, request);
    }
}
