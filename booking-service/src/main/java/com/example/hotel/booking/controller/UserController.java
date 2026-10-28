package com.example.hotel.booking.controller;

import com.example.hotel.booking.controller.dto.CreateUserRequest;
import com.example.hotel.booking.controller.dto.UpdateUserRequest;
import com.example.hotel.booking.controller.dto.UserPageResponse;
import com.example.hotel.booking.controller.dto.UserResponse;
import com.example.hotel.booking.service.UserService;

import org.springframework.security.access.prepost.PreAuthorize;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@Tag(name = "Users")
@SecurityRequirement(name = "userBearer")
@ApiResponses({
    @ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
    @ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthorized"),
    @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden")
})
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
class UserController {
    private final UserService userService;

    @Operation(summary = "Create a user",
        description = "Requires ADMIN")
    @ApiResponses({
        @ApiResponse(responseCode = "201", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "409", ref = "#/components/responses/UsernameAlreadyExists")
    })
    @PostMapping
    @ResponseStatus(CREATED)
    public UserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        return userService.createUser(request);
    }

    @Operation(summary = "List users",
        description = "Requires ADMIN. Ordered by ID ascending.")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @GetMapping
    public UserPageResponse listUsers(@RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return userService.listUsers(page, size);
    }

    @Operation(summary = "Get a user",
        description = "Requires ADMIN")
    @ApiResponses({
        @ApiResponse(responseCode = "200", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "404", ref = "#/components/responses/UserNotFound")
    })
    @GetMapping("/{id:[0-9]+}")
    public UserResponse getUser(@PathVariable @Positive long id) {
        return userService.getUser(id);
    }

    @Operation(summary = "Update a user",
        description = """
            Requires ADMIN. At least one field is required. Omitted fields keep their values.
            Null and unknown fields are rejected.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "404", ref = "#/components/responses/UserNotFound"),
        @ApiResponse(responseCode = "409", ref = "#/components/responses/UsernameAlreadyExists")
    })
    @PatchMapping("/{id:[0-9]+}")
    public UserResponse updateUser(@PathVariable @Positive long id,
                                   @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateUser(id, request);
    }

    @Operation(summary = "Delete a user",
        description = "Requires ADMIN. Users with bookings in any status cannot be deleted.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", content = @Content),
        @ApiResponse(responseCode = "404", ref = "#/components/responses/UserNotFound"),
        @ApiResponse(responseCode = "409", ref = "#/components/responses/UserHasBookings")
    })
    @DeleteMapping("/{id:[0-9]+}")
    @ResponseStatus(NO_CONTENT)
    public void deleteUser(@PathVariable @Positive long id) {
        userService.deleteUser(id);
    }
}
