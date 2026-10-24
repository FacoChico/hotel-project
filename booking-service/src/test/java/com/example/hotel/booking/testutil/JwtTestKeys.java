package com.example.hotel.booking.testutil;

import com.nimbusds.jose.JWSAlgorithm;
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
import java.util.Base64;
import java.util.Map;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import static java.nio.charset.StandardCharsets.US_ASCII;

@UtilityClass
public final class JwtTestKeys {
    public static final KeyPair KEY_PAIR = generateKeyPair(2048);
    public static final KeyPair OTHER_KEY_PAIR = generateKeyPair(2048);
    public static final String ISSUER = "hotel-booking";
    public static final String AUDIENCE = "hotel-api";
    private static final Path KEY_DIRECTORY = createKeyDirectory();
    private static final Path PUBLIC_KEY_PATH = writeKey("public.pem", pem("PUBLIC KEY", KEY_PAIR.getPublic().getEncoded()));
    private static final Path PRIVATE_KEY_PATH = writeKey("private.pem", pem("PRIVATE KEY", KEY_PAIR.getPrivate().getEncoded()));

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("security.jwt.public-key",
            () -> PUBLIC_KEY_PATH.toUri().toString());
        registry.add("security.jwt.private-key",
            () -> PRIVATE_KEY_PATH.toUri().toString());
        registry.add("security.jwt.issuer", () -> ISSUER);
        registry.add("security.jwt.audience", () -> AUDIENCE);
    }

    @SneakyThrows
    public static KeyPair generateKeyPair(int bits) {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    public static byte[] pem(String label, byte[] encoded) {
        var body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return ("-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n")
            .getBytes(US_ASCII);
    }

    @SneakyThrows
    public static String sign(Map<String, Object> claims, KeyPair keyPair, JWSAlgorithm algorithm) {
        var builder = new JWTClaimsSet.Builder();
        claims.forEach(builder::claim);
        var jwt = new SignedJWT(new JWSHeader(algorithm), builder.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }

    @SneakyThrows
    private static Path createKeyDirectory() {
        var parent = Files.createDirectories(Path.of("build", "tmp"));
        var directory = Files.createTempDirectory(parent, "jwt-test-");
        directory.toFile().deleteOnExit();
        return directory;
    }

    @SneakyThrows
    private static Path writeKey(String name, byte[] content) {
        var path = Files.write(KEY_DIRECTORY.resolve(name), content);
        path.toFile().deleteOnExit();
        return path;
    }
}
