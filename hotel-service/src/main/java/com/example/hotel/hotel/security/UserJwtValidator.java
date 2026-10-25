package com.example.hotel.hotel.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import static com.example.hotel.hotel.config.JwtConfig.CLOCK_SKEW;

@RequiredArgsConstructor
public final class UserJwtValidator implements OAuth2TokenValidator<Jwt> {
    private static final long TOKEN_LIFETIME_SECONDS = 3600;
    private static final Set<String> ROLES = Set.of("USER", "ADMIN");
    private static final OAuth2Error INVALID_CLAIMS = new OAuth2Error("invalid_token", "Invalid user token claims", null);
    private final String audience;
    private final Clock clock;

    @Override
    public @NonNull OAuth2TokenValidatorResult validate(@NonNull Jwt jwt) {
        if (isTokenInvalid(jwt)) {
            return OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
        }
        return OAuth2TokenValidatorResult.success();
    }

    private boolean isTokenInvalid(Jwt jwt) {
        return !hasValidSubject(jwt.getSubject())
            || !List.of(audience).equals(jwt.getAudience())
            || !hasValidRoles(jwt.getClaims().get("roles"))
            || !hasValidTimeWindow(jwt);
    }

    private static boolean hasValidSubject(String subject) {
        if (subject == null || !subject.matches("[1-9][0-9]*")) {
            return false;
        }

        try {
            return Long.parseLong(subject) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean hasValidRoles(Object roles) {
        return roles instanceof List<?> roleNames
            && !roleNames.isEmpty()
            && roleNames.stream()
                .allMatch(role -> role instanceof String name && ROLES.contains(name));
    }

    private boolean hasValidTimeWindow(Jwt jwt) {
        final var issuedAt = jwt.getIssuedAt();
        final var expiresAt = jwt.getExpiresAt();

        return issuedAt != null
            && expiresAt != null
            && expiresAt.isAfter(issuedAt)
            && !issuedAt.isAfter(clock.instant().plus(CLOCK_SKEW))
            && !expiresAt.isAfter(issuedAt.plusSeconds(TOKEN_LIFETIME_SECONDS));
    }
}
