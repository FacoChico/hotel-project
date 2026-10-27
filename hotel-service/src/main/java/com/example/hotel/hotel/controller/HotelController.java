package com.example.hotel.hotel.controller;

import com.example.hotel.hotel.controller.dto.CreateHotelRequest;
import com.example.hotel.hotel.controller.dto.HotelPageResponse;
import com.example.hotel.hotel.controller.dto.HotelResponse;
import com.example.hotel.hotel.controller.dto.UpdateHotelRequest;
import com.example.hotel.hotel.service.HotelService;

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

@Tag(name = "Hotels")
@SecurityRequirement(name = "userBearer")
@ApiResponses({
    @ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthorized"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden")
})
@RestController
@RequestMapping("/api/v1/hotels")
@RequiredArgsConstructor
class HotelController {
    private final HotelService hotelService;

    @Operation(summary = "Create a hotel",
        description = "Requires ADMIN")
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    @PostMapping
    @ResponseStatus(CREATED)
    private HotelResponse createHotel(@Valid @RequestBody CreateHotelRequest request) {
        return hotelService.createHotel(request);
    }

    @Operation(summary = "List hotels",
        description = "Requires USER or ADMIN. Ordered by ID ascending.")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @GetMapping
    private HotelPageResponse listHotels(@RequestParam(defaultValue = "0") @Min(0) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return hotelService.listHotels(page, size);
    }

    @Operation(summary = "Get a hotel",
        description = "Requires USER or ADMIN")
    @ApiResponses({
        @ApiResponse(responseCode = "200", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "404", ref = "#/components/responses/HotelNotFound")
    })
    @GetMapping("/{id:[0-9]+}")
    private HotelResponse getHotel(@PathVariable @Positive long id) {
        return hotelService.getHotel(id);
    }

    @Operation(summary = "Update a hotel",
        description = """
            Requires ADMIN. At least one field is required. Omitted fields keep their values.
            Null and unknown fields are rejected.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "404", ref = "#/components/responses/HotelNotFound")
    })
    @PatchMapping("/{id:[0-9]+}")
    private HotelResponse updateHotel(@PathVariable @Positive long id,
                                      @Valid @RequestBody UpdateHotelRequest request) {
        return hotelService.updateHotel(id, request);
    }

    @Operation(summary = "Delete a hotel",
        description = "Requires ADMIN. Hotels with rooms cannot be deleted.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", content = @Content),
        @ApiResponse(responseCode = "404", ref = "#/components/responses/HotelNotFound"),
        @ApiResponse(responseCode = "409", ref = "#/components/responses/HotelHasRooms")
    })
    @DeleteMapping("/{id:[0-9]+}")
    @ResponseStatus(NO_CONTENT)
    private void deleteHotel(@PathVariable @Positive long id) {
        hotelService.deleteHotel(id);
    }
}
