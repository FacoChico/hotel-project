package com.example.hotel.booking.security;

import com.example.hotel.booking.config.JwtConfig;
import com.example.hotel.booking.properties.JwtProperties;
import com.example.hotel.booking.properties.ServiceJwtProperties;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;

import lombok.SneakyThrows;

import static com.example.hotel.booking.testutil.JwtTestKeys.AUDIENCE;
import static com.example.hotel.booking.testutil.JwtTestKeys.ISSUER;
import static com.example.hotel.booking.testutil.JwtTestKeys.KEY_PAIR;
import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class InternalTokenServiceTest {

    @Test
    @SneakyThrows
    void shouldIssueSignedServiceTokenWhenInternalAuthenticationIsRequested() {
        final var now = Instant.parse("2026-10-25T10:00:00Z");
        final var clock = Clock.fixed(now, UTC);
        final var properties = new JwtProperties(ISSUER, AUDIENCE, null, null);
        final var encoder = new JwtConfig().jwtEncoder(KEY_PAIR);
        final var service = new InternalTokenService(encoder, properties,
            new ServiceJwtProperties("hotel-internal"), clock);

        final var token = service.issueToken();
        final var signedToken = SignedJWT.parse(token);
        final var claims = signedToken.getJWTClaimsSet();

        assertThat(signedToken.verify(new RSASSAVerifier((RSAPublicKey) KEY_PAIR.getPublic()))).isTrue();
        assertThat(signedToken.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(claims.getSubject()).isEqualTo("booking-service");
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getAudience()).containsExactly("hotel-internal");
        assertThat(claims.getStringClaim("scope")).isEqualTo("reservations:write");
        assertThat(claims.getClaim("roles")).isNull();
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(now);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(now.plusSeconds(120));
    }

    @Test
    void shouldRejectConfigurationWhenAudiencesAreIdentical() {
        final var properties = new JwtProperties(ISSUER, AUDIENCE, null, null);
        final var encoder = new JwtConfig().jwtEncoder(KEY_PAIR);

        final var exception = catchThrowable(() -> new InternalTokenService(
            encoder, properties, new ServiceJwtProperties(AUDIENCE), Clock.systemUTC()));

        assertThat(exception)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("User and service JWT audiences must differ");
    }
}
