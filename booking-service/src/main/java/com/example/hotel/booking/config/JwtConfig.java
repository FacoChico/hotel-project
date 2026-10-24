package com.example.hotel.booking.config;

import com.example.hotel.booking.properties.JwtProperties;
import com.example.hotel.booking.security.UserJwtValidator;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;

import static org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    @Bean
    Clock jwtClock() {
        return Clock.systemUTC();
    }

    @Bean
    KeyPair jwtKeyPair(JwtProperties properties) {
        try (var publicInput = properties.publicKey().getInputStream();
             var privateInput = properties.privateKey().getInputStream()) {
            var publicKey = RsaKeyConverters.x509().convert(publicInput);
            var privateKey = RsaKeyConverters.pkcs8().convert(privateInput);

            if (publicKey.getModulus().bitLength() < 2048
                || !publicKey.getModulus().equals(privateKey.getModulus())) {
                throw new IllegalArgumentException("A matching RSA key pair of at least 2048 bits is required");
            }
            return new KeyPair(publicKey, privateKey);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("JWT keys must be readable, matching RSA public and PKCS#8 private keys", exception);
        }
    }

    @Bean
    public JwtEncoder jwtEncoder(KeyPair jwtKeyPair) {
        var rsaKey = new RSAKey.Builder((RSAPublicKey) jwtKeyPair.getPublic())
            .privateKey((RSAPrivateKey) jwtKeyPair.getPrivate())
            .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    @Bean
    JwtDecoder jwtDecoder(KeyPair jwtKeyPair, JwtProperties properties, Clock jwtClock) {
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) jwtKeyPair.getPublic())
            .signatureAlgorithm(RS256)
            .build();
        var timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(jwtClock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            timestamps,
            new JwtIssuerValidator(properties.issuer()),
            new UserJwtValidator(properties.audience(), jwtClock)));
        return decoder;
    }
}
