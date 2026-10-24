package com.example.hotel.booking.service;

import com.example.hotel.booking.controller.dto.CredentialsRequest;
import com.example.hotel.booking.controller.dto.RegisterResponse;
import com.example.hotel.booking.controller.dto.TokenResponse;
import com.example.hotel.booking.controller.dto.UserResponse;
import com.example.hotel.booking.security.JwtTokenService;
import com.example.hotel.booking.service.exception.UsernameAlreadyExistsException;
import com.example.hotel.booking.domain.User;
import com.example.hotel.booking.repository.UserRepository;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.hotel.booking.security.JwtTokenService.TOKEN_LIFETIME_SECONDS;
import static com.example.hotel.booking.domain.UserRole.USER;
import static java.util.Locale.ROOT;
import static java.util.UUID.randomUUID;

@Service
public class UserAuthenticationService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final String dummyPasswordHash;

    public UserAuthenticationService(UserRepository userRepository,
                                     PasswordEncoder passwordEncoder,
                                     JwtTokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyPasswordHash = passwordEncoder.encode(randomUUID().toString());
    }

    @Transactional
    public RegisterResponse register(CredentialsRequest credentials) {
        var passwordHash = passwordEncoder.encode(credentials.password());
        var user = new User(credentials.username(), passwordHash, USER);

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (isUsernameConflict(exception)) {
                throw new UsernameAlreadyExistsException(exception);
            }
            throw exception;
        }

        return new RegisterResponse(
            new UserResponse(user.getId(), user.getUsername(), user.getRole()), issueToken(user));
    }

    @Transactional(readOnly = true)
    public TokenResponse authenticate(CredentialsRequest credentials) {
        var user = userRepository.findByUsername(credentials.username()).orElse(null);
        var passwordHash = user == null
            ? dummyPasswordHash
            : user.getPasswordHash();

        var passwordMatches = passwordEncoder.matches(credentials.password(), passwordHash);
        if (user == null || !passwordMatches) {
            throw new BadCredentialsException("Invalid username or password");
        }

        return issueToken(user);
    }

    private boolean isUsernameConflict(DataIntegrityViolationException exception) {
        return exception.getCause() instanceof ConstraintViolationException constraint
            && constraint.getConstraintName() != null
            && constraint.getConstraintName().toLowerCase(ROOT).contains("uk_users_username");
    }

    private TokenResponse issueToken(User user) {
        return new TokenResponse(tokenService.issueToken(user.getId(), user.getRole()), "Bearer", TOKEN_LIFETIME_SECONDS);
    }
}
