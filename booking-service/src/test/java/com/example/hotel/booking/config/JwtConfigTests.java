package com.example.hotel.booking.config;

import com.example.hotel.booking.properties.JwtProperties;
import com.example.hotel.booking.properties.ServiceJwtProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.core.io.ByteArrayResource;

import java.security.KeyPair;
import java.util.stream.Stream;

import static com.example.hotel.booking.testutil.JwtTestKeys.AUDIENCE;
import static com.example.hotel.booking.testutil.JwtTestKeys.ISSUER;
import static com.example.hotel.booking.testutil.JwtTestKeys.KEY_PAIR;
import static com.example.hotel.booking.testutil.JwtTestKeys.OTHER_KEY_PAIR;
import static com.example.hotel.booking.testutil.JwtTestKeys.generateKeyPair;
import static com.example.hotel.booking.testutil.JwtTestKeys.pem;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class JwtConfigTests {

    @Test
    void shouldFailStartupWhenJwtSettingsAreMissing() {
        var contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(JwtConfig.class, TestPropertiesConfig.class)
            .withPropertyValues("security.service-jwt.audience=hotel-internal");

        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("security.jwt");
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidKeyPairs")
    void shouldRejectKeysWhenConfigurationIsInvalid(String scenario, byte[] publicKey, byte[] privateKey) {
        var properties = new JwtProperties(ISSUER, AUDIENCE,
            new ByteArrayResource(publicKey), new ByteArrayResource(privateKey));
        var configuration = new JwtConfig();

        var exception = catchThrowable(() -> configuration.jwtKeyPair(properties));

        assertThat(exception)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("JWT keys must be readable, matching RSA public and PKCS#8 private keys");
    }

    static Stream<Arguments> invalidKeyPairs() {
        var publicPem = pem("PUBLIC KEY", KEY_PAIR.getPublic().getEncoded());
        var privatePem = pem("PRIVATE KEY", KEY_PAIR.getPrivate().getEncoded());
        var weakKeyPair = generateKeyPair(1024);
        return Stream.of(
            arguments("malformed public key", "invalid PEM".getBytes(US_ASCII), privatePem),
            arguments("malformed private key", publicPem, "invalid PEM".getBytes(US_ASCII)),
            arguments("mismatched keys", publicPem, pem("PRIVATE KEY", OTHER_KEY_PAIR.getPrivate().getEncoded())),
            weakKeyArguments(weakKeyPair));
    }

    private static Arguments weakKeyArguments(KeyPair keyPair) {
        return arguments("weak RSA key",
            pem("PUBLIC KEY", keyPair.getPublic().getEncoded()),
            pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({JwtProperties.class, ServiceJwtProperties.class})
    static class TestPropertiesConfig {
    }
}
