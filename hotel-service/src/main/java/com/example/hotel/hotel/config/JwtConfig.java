package com.example.hotel.hotel.config;

import com.example.hotel.hotel.properties.JwtProperties;
import com.example.hotel.hotel.properties.ServiceJwtProperties;
import com.example.hotel.hotel.security.ServiceJwtValidator;
import com.example.hotel.hotel.security.UserJwtValidator;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;

import static org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({JwtProperties.class, ServiceJwtProperties.class})
public class JwtConfig {
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    @Bean
    private static Clock jwtClock() {
        return Clock.systemUTC();
    }

    @Bean
    private static RSAPublicKey jwtPublicKey(JwtProperties properties) {
        try (final var input = properties.publicKey().getInputStream()) {
            final var publicKey = RsaKeyConverters.x509().convert(input);
            if (publicKey.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("An RSA public key of at least 2048 bits is required");
            }
            return publicKey;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("JWT public key must be a readable X.509 RSA key of at least 2048 bits", exception);
        }
    }

    @Bean
    private static JwtDecoder userJwtDecoder(RSAPublicKey jwtPublicKey, JwtProperties properties, Clock jwtClock) {
        return createDecoder(jwtPublicKey, properties.issuer(), jwtClock,
            new UserJwtValidator(properties.audience(), jwtClock));
    }

    @Bean
    private static JwtDecoder serviceJwtDecoder(RSAPublicKey jwtPublicKey,
                                                JwtProperties properties,
                                                ServiceJwtProperties serviceProperties,
                                                Clock jwtClock) {
        if (properties.audience().equals(serviceProperties.audience())) {
            throw new IllegalStateException("User and service JWT audiences must differ");
        }

        return createDecoder(jwtPublicKey, properties.issuer(), jwtClock,
            new ServiceJwtValidator(serviceProperties.audience(), jwtClock));
    }

    private static JwtDecoder createDecoder(RSAPublicKey publicKey,
                                            String issuer,
                                            Clock clock,
                                            OAuth2TokenValidator<Jwt> claimsValidator) {
        final var decoder = NimbusJwtDecoder.withPublicKey(publicKey)
            .signatureAlgorithm(RS256)
            .build();
        final var timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            timestamps, new JwtIssuerValidator(issuer), claimsValidator));
        return decoder;
    }
}
