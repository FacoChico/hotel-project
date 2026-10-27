package com.example.hotel.hotel.controller;

import com.example.hotel.hotel.controller.dto.CreateRoomRequest;
import com.example.hotel.hotel.controller.dto.RoomPageResponse;
import com.example.hotel.hotel.controller.dto.RoomResponse;
import com.example.hotel.hotel.controller.dto.RoomStatisticsPageResponse;
import com.example.hotel.hotel.controller.dto.UpdateRoomRequest;
import com.example.hotel.hotel.service.RoomService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@Tag(name = "Rooms")
@SecurityRequirement(name = "userBearer")
@ApiResponses({
    @ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthorized"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden")
})
@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
class RoomController {
    private static final String DATE_PATTERN = "uuuu-MM-dd";

    private final RoomService roomService;

    @Operation(summary = "Create a room",
        description = "Requires ADMIN. Room numbers are unique within a hotel.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "404", ref = "#/components/responses/HotelNotFound"),
        @ApiResponse(responseCode = "409", ref = "#/components/responses/RoomNumberAlreadyExists")
    })
    @PostMapping
    @ResponseStatus(CREATED)
    private RoomResponse createRoom(@Valid @RequestBody CreateRoomRequest request) {
        return roomService.createRoom(request);
    }

    @Operation(summary = "Find available rooms",
        description = """
            Requires USER or ADMIN. startDate must be before endDate. Past dates are allowed.
            Returns rooms with available=true and no overlapping RESERVED stays, ordered by ID ascending.
            Search does not reserve a room.
            """)
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @GetMapping
    private RoomPageResponse listAvailableRooms(@RequestParam @DateTimeFormat(pattern = DATE_PATTERN) LocalDate startDate,
                                                @RequestParam @DateTimeFormat(pattern = DATE_PATTERN) LocalDate endDate,
                                                @RequestParam(required = false) @Positive Long hotelId,
                                                @RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return roomService.listAvailableRooms(startDate, endDate, hotelId, page, size);
    }

    @Operation(summary = "Recommend available rooms",
        description = "Requires USER or ADMIN. Available rooms are ordered by timesBooked ascending, then ID ascending.")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @GetMapping("/recommend")
    private RoomPageResponse recommendAvailableRooms(@RequestParam @DateTimeFormat(pattern = DATE_PATTERN) LocalDate startDate,
                                                     @RequestParam @DateTimeFormat(pattern = DATE_PATTERN) LocalDate endDate,
                                                     @RequestParam(required = false) @Positive Long hotelId,
                                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return roomService.recommendAvailableRooms(startDate, endDate, hotelId, page, size);
    }

    @Operation(summary = "Get room occupancy statistics",
        description = """
            Requires ADMIN. Includes unavailable rooms and rooms without reservations.
            bookedNights counts RESERVED nights within the requested interval.
            occupancyRate is bookedNights / totalNights. timesBooked is not limited to the requested interval.
            """)
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @GetMapping("/statistics")
    private RoomStatisticsPageResponse getOccupancyStatistics(@RequestParam @DateTimeFormat(pattern = DATE_PATTERN) LocalDate startDate,
                                                              @RequestParam @DateTimeFormat(pattern = DATE_PATTERN) LocalDate endDate,
                                                              @RequestParam(required = false) @Positive Long hotelId,
                                                              @RequestParam(defaultValue = "0") @Min(0) int page,
                                                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return roomService.getOccupancyStatistics(startDate, endDate, hotelId, page, size);
    }

    @Operation(summary = "Get a room",
        description = "Requires USER or ADMIN")
    @ApiResponses({
        @ApiResponse(responseCode = "200", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "404", ref = "#/components/responses/RoomNotFound")
    })
    @GetMapping("/{id:[0-9]+}")
    private RoomResponse getRoom(@PathVariable @Positive long id) {
        return roomService.getRoom(id);
    }

    @Operation(summary = "Update a room",
        description = """
            Requires ADMIN. Omitted fields keep their values. Null and unknown fields are rejected.
            A room cannot be disabled if a RESERVED stay ends after the current UTC date.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "404", ref = "#/components/responses/RoomNotFound"),
        @ApiResponse(responseCode = "409", ref = "#/components/responses/RoomUpdateConflict"),
        @ApiResponse(responseCode = "503", ref = "#/components/responses/RoomBusy")
    })
    @PatchMapping("/{id:[0-9]+}")
    private RoomResponse updateRoom(@PathVariable @Positive long id,
                                    @Valid @RequestBody UpdateRoomRequest request) {
        return roomService.updateRoom(id, request);
    }

    @Operation(summary = "Delete a room",
        description = "Requires ADMIN. Rooms with RESERVED or RELEASED records cannot be deleted.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", content = @Content),
        @ApiResponse(responseCode = "404", ref = "#/components/responses/RoomNotFound"),
        @ApiResponse(responseCode = "409", ref = "#/components/responses/RoomHasReservations"),
        @ApiResponse(responseCode = "503", ref = "#/components/responses/RoomBusy")
    })
    @DeleteMapping("/{id:[0-9]+}")
    @ResponseStatus(NO_CONTENT)
    private void deleteRoom(@PathVariable @Positive long id) {
        roomService.deleteRoom(id);
    }
}
