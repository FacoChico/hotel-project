package com.example.hotel.booking.security;

import com.example.hotel.booking.properties.JwtProperties;
import com.example.hotel.booking.user.UserRole;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

import lombok.RequiredArgsConstructor;

import static java.time.temporal.ChronoUnit.SECONDS;
import static org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256;

@Service
@RequiredArgsConstructor
public class JwtTokenService {
    static final long TOKEN_LIFETIME_SECONDS = 3600;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock jwtClock;

    public String issueToken(long userId, UserRole role) {
        if (userId <= 0) {
            throw new IllegalArgumentException("A positive user ID is required");
        }
        if (role == null) {
            throw new IllegalArgumentException("A user role is required");
        }

        var issuedAt = jwtClock.instant().truncatedTo(SECONDS);
        var claims = JwtClaimsSet.builder()
            .subject(Long.toString(userId))
            .issuer(properties.issuer())
            .audience(List.of(properties.audience()))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(TOKEN_LIFETIME_SECONDS))
            .claim("roles", List.of(role.name()))
            .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(RS256).build(), claims))
            .getTokenValue();
    }
}
