package com.example.hotel.booking.controller;

import com.example.hotel.booking.controller.dto.CredentialsRequest;
import com.example.hotel.booking.controller.dto.RegisterResponse;
import com.example.hotel.booking.controller.dto.TokenResponse;
import com.example.hotel.booking.service.UserAuthenticationService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import static org.springframework.http.HttpStatus.CREATED;

@Tag(name = "Authentication")
@ApiResponses({
    @ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthorized")
})
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
class UserAuthenticationController {
    private final UserAuthenticationService authenticationService;

    @Operation(summary = "Register a USER and issue a JWT",
        description = "No authentication required. Usernames are unique and case-sensitive.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "409", ref = "#/components/responses/UsernameAlreadyExists")
    })
    @PostMapping("/register")
    @ResponseStatus(CREATED)
    RegisterResponse register(@Valid @RequestBody CredentialsRequest credentials) {
        return authenticationService.register(credentials);
    }

    @Operation(summary = "Authenticate and issue a JWT",
        description = "No authentication required. The JWT expires after one hour.")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @PostMapping("/auth")
    TokenResponse authenticate(@Valid @RequestBody CredentialsRequest credentials) {
        return authenticationService.authenticate(credentials);
    }
}
