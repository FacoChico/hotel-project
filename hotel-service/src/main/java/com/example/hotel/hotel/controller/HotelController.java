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

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/v1/hotels")
@RequiredArgsConstructor
class HotelController {
    private final HotelService hotelService;

    @PostMapping
    @ResponseStatus(CREATED)
    private HotelResponse createHotel(@Valid @RequestBody CreateHotelRequest request) {
        return hotelService.createHotel(request);
    }

    @GetMapping
    private HotelPageResponse listHotels(@RequestParam(defaultValue = "0") @Min(0) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return hotelService.listHotels(page, size);
    }

    @GetMapping("/{id}")
    private HotelResponse getHotel(@PathVariable @Positive long id) {
        return hotelService.getHotel(id);
    }

    @PatchMapping("/{id}")
    private HotelResponse updateHotel(@PathVariable @Positive long id,
                                      @Valid @RequestBody UpdateHotelRequest request) {
        return hotelService.updateHotel(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(NO_CONTENT)
    private void deleteHotel(@PathVariable @Positive long id) {
        hotelService.deleteHotel(id);
    }
}
