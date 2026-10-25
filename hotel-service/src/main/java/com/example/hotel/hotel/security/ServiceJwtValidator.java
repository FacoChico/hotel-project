package com.example.hotel.hotel.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.util.List;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import static com.example.hotel.hotel.config.JwtConfig.CLOCK_SKEW;

@RequiredArgsConstructor
public final class ServiceJwtValidator implements OAuth2TokenValidator<Jwt> {
    private static final long TOKEN_LIFETIME_SECONDS = 120;
    private static final OAuth2Error INVALID_CLAIMS = new OAuth2Error("invalid_token", "Invalid service token claims", null);
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
        final var scope = jwt.getClaims().get("scope");

        return !"booking-service".equals(jwt.getSubject())
            || !List.of(audience).equals(jwt.getAudience())
            || !hasValidTimeWindow(jwt)
            || scope != null && !(scope instanceof String);
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
