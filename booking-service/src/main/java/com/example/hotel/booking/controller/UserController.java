package com.example.hotel.booking.controller;

import com.example.hotel.booking.controller.dto.CreateUserRequest;
import com.example.hotel.booking.controller.dto.UpdateUserRequest;
import com.example.hotel.booking.controller.dto.UserPageResponse;
import com.example.hotel.booking.controller.dto.UserResponse;
import com.example.hotel.booking.service.UserService;

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
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
class UserController {
    private final UserService userService;

    @PostMapping
    @ResponseStatus(CREATED)
    private UserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        return userService.createUser(request);
    }

    @GetMapping
    private UserPageResponse listUsers(@RequestParam(defaultValue = "0") @Min(0) int page,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return userService.listUsers(page, size);
    }

    @GetMapping("/{id}")
    private UserResponse getUser(@PathVariable @Positive long id) {
        return userService.getUser(id);
    }

    @PatchMapping("/{id}")
    private UserResponse updateUser(@PathVariable @Positive long id,
                                    @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateUser(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(NO_CONTENT)
    private void deleteUser(@PathVariable @Positive long id) {
        userService.deleteUser(id);
    }
}
