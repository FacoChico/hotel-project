package com.example.hotel.hotel.config;

import com.example.hotel.hotel.testutil.JwtTestKeys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;

import lombok.SneakyThrows;

import static com.example.hotel.hotel.testutil.JwtTestKeys.generateKeyPair;
import static com.example.hotel.hotel.testutil.JwtTestKeys.pem;
import static org.assertj.core.api.Assertions.assertThat;

class JwtConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(JwtConfig.class)
        .withPropertyValues("security.jwt.issuer=hotel-booking", "security.jwt.audience=hotel-api",
            "security.service-jwt.audience=hotel-internal");

    @Test
    void shouldFailStartupWhenPublicKeyIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("security.jwt");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "weak", "unreadable"})
    @SneakyThrows
    void shouldFailStartupWhenPublicKeyIsInvalid(String scenario) {
        final var directory = Files.createDirectories(Path.of("build", "tmp"));
        final var keyFile = Files.createTempFile(directory, "invalid-public-", ".pem");
        try {
            switch (scenario) {
                case "malformed" -> Files.writeString(keyFile, "invalid PEM");
                case "weak" -> Files.write(keyFile, pem("PUBLIC KEY", generateKeyPair(1024).getPublic().getEncoded()));
                case "unreadable" -> Files.delete(keyFile);
                default -> throw new IllegalArgumentException(scenario);
            }

            contextRunner.withPropertyValues("security.jwt.public-key=" + keyFile.toUri())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining(
                        "JWT public key must be a readable X.509 RSA key of at least 2048 bits");
                });
        } finally {
            Files.deleteIfExists(keyFile);
        }
    }

    @Test
    @SneakyThrows
    void shouldFailStartupWhenAudiencesAreIdentical() {
        final var directory = Files.createDirectories(Path.of("build", "tmp"));
        final var keyFile = Files.createTempFile(directory, "public-", ".pem");
        try {
            Files.write(keyFile, pem("PUBLIC KEY", JwtTestKeys.KEY_PAIR.getPublic().getEncoded()));

            contextRunner.withPropertyValues("security.jwt.public-key=" + keyFile.toUri(),
                    "security.service-jwt.audience=hotel-api")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("User and service JWT audiences must differ");
                });
        } finally {
            Files.deleteIfExists(keyFile);
        }
    }
}
