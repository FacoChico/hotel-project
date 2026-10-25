package com.example.hotel.booking.service;

import com.example.hotel.booking.controller.dto.CreateUserRequest;
import com.example.hotel.booking.controller.dto.CredentialsRequest;
import com.example.hotel.booking.controller.dto.RegisterResponse;
import com.example.hotel.booking.controller.dto.TokenResponse;
import com.example.hotel.booking.domain.UserRole;
import com.example.hotel.booking.repository.UserRepository;
import com.example.hotel.booking.security.JwtTokenService;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.hotel.booking.domain.UserRole.USER;
import static com.example.hotel.booking.security.JwtTokenService.TOKEN_LIFETIME_SECONDS;
import static java.util.UUID.randomUUID;

@Service
public class UserAuthenticationService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final UserService userService;
    private final String emptyUserPasswordHash;

    public UserAuthenticationService(UserRepository userRepository,
                                     PasswordEncoder passwordEncoder,
                                     JwtTokenService tokenService,
                                     UserService userService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.userService = userService;
        this.emptyUserPasswordHash = passwordEncoder.encode(randomUUID().toString());
    }

    @Transactional
    public RegisterResponse register(CredentialsRequest credentials) {
        final var request = new CreateUserRequest(credentials.username(), credentials.password(), USER);
        final var user = userService.createUser(request);
        return new RegisterResponse(user, issueToken(user.id(), user.role()));
    }

    @Transactional(readOnly = true)
    public TokenResponse authenticate(CredentialsRequest credentials) {
        final var user = userRepository.findByUsername(credentials.username()).orElse(null);
        final var passwordHash = user == null
            ? emptyUserPasswordHash
            : user.getPasswordHash();

        final var passwordMatches = passwordEncoder.matches(credentials.password(), passwordHash);
        if (user == null || !passwordMatches) {
            throw new BadCredentialsException("Invalid username or password");
        }

        return issueToken(user.getId(), user.getRole());
    }

    private TokenResponse issueToken(long userId, UserRole role) {
        return new TokenResponse(tokenService.issueToken(userId, role), "Bearer", TOKEN_LIFETIME_SECONDS);
    }
}
