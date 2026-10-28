package com.example.hotel.gateway.config;

import com.example.hotel.gateway.properties.JwtProperties;
import com.example.hotel.gateway.security.SecurityExceptionHandler;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.util.List;

import static jakarta.servlet.DispatcherType.ERROR;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

@EnableConfigurationProperties(JwtProperties.class)
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    private static JwtDecoder jwtDecoder(JwtProperties properties) {
        try (final var input = properties.publicKey().getInputStream()) {
            final var key = RsaKeyConverters.x509().convert(input);
            if (key.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("JWT requires an RSA key of at least 2048 bits");
            }
            final var decoder = NimbusJwtDecoder.withPublicKey(key).build();
            final var audience = new JwtClaimValidator<List<String>>("aud",
                values -> values != null && values.contains(properties.audience()));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), audience));
            return decoder;
        } catch (IOException exception) {
            throw new IllegalStateException("JWT public key must be readable", exception);
        }
    }

    @Bean
    private static SecurityFilterChain gatewaySecurityFilterChain(HttpSecurity http, SecurityExceptionHandler errors) {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(access -> access
                .dispatcherTypeMatchers(ERROR).permitAll()
                .requestMatchers(POST, "/api/v1/users/register", "/api/v1/users/auth",
                    "/api/v1/user/register", "/api/v1/user/auth").permitAll()
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(handling -> handling.authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> {})
                .authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .build();
    }
}
