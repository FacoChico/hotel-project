package com.example.hotel.booking.security;

import com.example.hotel.booking.properties.JwtProperties;
import com.example.hotel.booking.properties.ServiceJwtProperties;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

import static java.time.temporal.ChronoUnit.SECONDS;
import static org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256;

@Service
public class InternalTokenService {
    private static final long TOKEN_LIFETIME_SECONDS = 120;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final ServiceJwtProperties serviceProperties;
    private final Clock jwtClock;

    public InternalTokenService(JwtEncoder jwtEncoder,
                                JwtProperties properties,
                                ServiceJwtProperties serviceProperties,
                                Clock jwtClock) {
        if (properties.audience().equals(serviceProperties.audience())) {
            throw new IllegalStateException("User and service JWT audiences must differ");
        }

        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.serviceProperties = serviceProperties;
        this.jwtClock = jwtClock;
    }

    public String issueToken() {
        final var issuedAt = jwtClock.instant().truncatedTo(SECONDS);
        final var claims = JwtClaimsSet.builder()
            .subject("booking-service")
            .issuer(properties.issuer())
            .audience(List.of(serviceProperties.audience()))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(TOKEN_LIFETIME_SECONDS))
            .claim("scope", "reservations:write")
            .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(RS256).build(), claims))
            .getTokenValue();
    }
}
