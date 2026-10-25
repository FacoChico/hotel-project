package com.example.hotel.booking.service;

import com.example.hotel.booking.controller.dto.CredentialsRequest;
import com.example.hotel.booking.domain.User;
import com.example.hotel.booking.properties.AdminBootstrapProperties;
import com.example.hotel.booking.repository.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

import static com.example.hotel.booking.domain.UserRole.ADMIN;
import static java.util.stream.Collectors.joining;

@Service
@RequiredArgsConstructor
public class AdminBootstrapService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;

    @Transactional
    public void initializeAdmin(AdminBootstrapProperties properties) {
        final var credentialsRequest = new CredentialsRequest(properties.username(), properties.password());
        final var invalidFields = validator.validate(credentialsRequest).stream()
            .map(violation -> violation.getPropertyPath().toString())
            .distinct()
            .sorted()
            .collect(joining(", "));
        if (!invalidFields.isEmpty()) {
            throw new IllegalStateException("Invalid bootstrap admin credentials: " + invalidFields);
        }

        final var existingUser = userRepository.findByUsername(properties.username()).orElse(null);
        if (existingUser != null) {
            if (existingUser.getRole() != ADMIN) {
                throw new IllegalStateException("Bootstrap administrator username is already assigned to a non-admin user");
            }
            return;
        }

        final var passwordHash = passwordEncoder.encode(properties.password());
        userRepository.save(new User(properties.username(), passwordHash, ADMIN));
    }
}
