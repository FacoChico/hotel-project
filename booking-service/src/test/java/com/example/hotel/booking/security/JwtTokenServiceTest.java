package com.example.hotel.booking.security;

import com.example.hotel.booking.config.JwtConfig;
import com.example.hotel.booking.domain.UserRole;
import com.example.hotel.booking.properties.JwtProperties;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import lombok.SneakyThrows;

import static com.example.hotel.booking.testutil.JwtTestKeys.AUDIENCE;
import static com.example.hotel.booking.testutil.JwtTestKeys.ISSUER;
import static com.example.hotel.booking.testutil.JwtTestKeys.KEY_PAIR;
import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenServiceTest {

    @ParameterizedTest
    @EnumSource(UserRole.class)
    @SneakyThrows
    void shouldIssueSignedTokenWhenUserHasSupportedRole(UserRole role) {
        final var now = Instant.parse("2026-10-24T10:00:00Z");
        final var clock = Clock.fixed(now, ZoneOffset.UTC);
        final var properties = new JwtProperties(ISSUER, AUDIENCE, null, null);
        final var encoder = new JwtConfig().jwtEncoder(KEY_PAIR);
        final var service = new JwtTokenService(encoder, properties, clock);

        final var token = service.issueToken(42, role);
        final var signedToken = SignedJWT.parse(token);
        final var claims = signedToken.getJWTClaimsSet();

        assertThat(signedToken.verify(new RSASSAVerifier((RSAPublicKey) KEY_PAIR.getPublic()))).isTrue();
        assertThat(signedToken.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getAudience()).containsExactly(AUDIENCE);
        assertThat(claims.getStringListClaim("roles")).containsExactly(role.name());
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(now);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(now.plusSeconds(3600));
    }
}
