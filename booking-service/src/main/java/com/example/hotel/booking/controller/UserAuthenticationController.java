package com.example.hotel.booking.controller;

import com.example.hotel.booking.service.UserAuthenticationService;
import com.example.hotel.booking.controller.dto.CredentialsRequest;
import com.example.hotel.booking.controller.dto.RegisterResponse;
import com.example.hotel.booking.controller.dto.TokenResponse;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import static org.springframework.http.HttpStatus.CREATED;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
class UserAuthenticationController {
    private final UserAuthenticationService authenticationService;

    @PostMapping("/register")
    @ResponseStatus(CREATED)
    RegisterResponse register(@Valid @RequestBody CredentialsRequest credentials) {
        return authenticationService.register(credentials);
    }

    @PostMapping("/auth")
    TokenResponse authenticate(@Valid @RequestBody CredentialsRequest credentials) {
        return authenticationService.authenticate(credentials);
    }
}
