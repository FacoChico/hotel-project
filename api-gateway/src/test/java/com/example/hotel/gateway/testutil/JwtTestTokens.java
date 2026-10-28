package com.example.hotel.gateway.testutil;

import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import static com.nimbusds.jose.JWSAlgorithm.RS256;
import static java.nio.charset.StandardCharsets.US_ASCII;

@UtilityClass
public class JwtTestTokens {
    private static final KeyPair KEY_PAIR = generateKeys();
    private static final Path PUBLIC_KEY = writePublicKey();
    public static final String USER_TOKEN = token("hotel-api", Instant.now().plusSeconds(3600));

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("security.jwt.public-key", () -> PUBLIC_KEY.toUri().toString());
    }

    @SneakyThrows
    public static String token(String audience, Instant expiry) {
        final var claims = new JWTClaimsSet.Builder()
            .subject("42")
            .issuer("hotel-booking")
            .audience(audience)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(expiry))
            .claim("roles", List.of("USER"))
            .build();
        final var jwt = new SignedJWT(new JWSHeader(RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
        return jwt.serialize();
    }

    @SneakyThrows
    private static KeyPair generateKeys() {
        final var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @SneakyThrows
    private static Path writePublicKey() {
        final var directory = Files.createDirectories(Path.of("build", "tmp"));
        final var path = Files.createTempFile(directory, "gateway-public-", ".pem");
        final var body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(KEY_PAIR.getPublic().getEncoded());
        Files.writeString(path, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n", US_ASCII);
        path.toFile().deleteOnExit();
        return path;
    }
}
