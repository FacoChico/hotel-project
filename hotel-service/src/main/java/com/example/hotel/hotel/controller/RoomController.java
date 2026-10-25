package com.example.hotel.hotel.controller;

import com.example.hotel.hotel.controller.dto.CreateRoomRequest;
import com.example.hotel.hotel.controller.dto.RoomResponse;
import com.example.hotel.hotel.controller.dto.UpdateRoomRequest;
import com.example.hotel.hotel.service.RoomService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
class RoomController {
    private final RoomService roomService;

    @PostMapping
    @ResponseStatus(CREATED)
    private RoomResponse createRoom(@Valid @RequestBody CreateRoomRequest request) {
        return roomService.createRoom(request);
    }

    @GetMapping("/{id:[0-9]+}")
    private RoomResponse getRoom(@PathVariable @Positive long id) {
        return roomService.getRoom(id);
    }

    @PatchMapping("/{id:[0-9]+}")
    private RoomResponse updateRoom(@PathVariable @Positive long id,
                                    @Valid @RequestBody UpdateRoomRequest request) {
        return roomService.updateRoom(id, request);
    }

    @DeleteMapping("/{id:[0-9]+}")
    @ResponseStatus(NO_CONTENT)
    private void deleteRoom(@PathVariable @Positive long id) {
        roomService.deleteRoom(id);
    }
}
