package com.example.hotel.hotel.config;

import com.example.hotel.hotel.security.SecurityExceptionHandler;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import static jakarta.servlet.DispatcherType.ERROR;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {
    private static final String CONFIRM_AVAILABILITY = "/api/v1/rooms/{id}/confirm-availability";
    private static final String RELEASE = "/api/v1/rooms/{id}/release";

    @Bean
    @Order(1)
    private static SecurityFilterChain internalSecurityFilterChain(HttpSecurity http,
                                                                   SecurityExceptionHandler problemHandler,
                                                                   @Qualifier("serviceJwtDecoder") JwtDecoder decoder) {
        return configureStatelessSecurity(http, problemHandler)
            .securityMatcher(CONFIRM_AVAILABILITY, RELEASE)
            .authorizeHttpRequests(access -> access
                .dispatcherTypeMatchers(ERROR).permitAll()
                .requestMatchers(POST, CONFIRM_AVAILABILITY, RELEASE).hasAuthority("reservations:write")
                .anyRequest().denyAll())
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.decoder(decoder)
                    .jwtAuthenticationConverter(authenticationConverter("scope", "")))
                .authenticationEntryPoint(problemHandler)
                .accessDeniedHandler(problemHandler))
            .build();
    }

    @Bean
    @Order(2)
    private static SecurityFilterChain publicSecurityFilterChain(HttpSecurity http,
                                                                 SecurityExceptionHandler problemHandler,
                                                                 @Qualifier("userJwtDecoder") JwtDecoder decoder) {
        return configureStatelessSecurity(http, problemHandler)
            .authorizeHttpRequests(access -> access
                .dispatcherTypeMatchers(ERROR).permitAll()
                .requestMatchers(GET, "/actuator/health").permitAll()
                .requestMatchers(GET, "/actuator/info", "/api/v1/rooms/statistics").hasRole("ADMIN")
                .requestMatchers(GET, "/api/v1/hotels", "/api/v1/hotels/{id}",
                    "/api/v1/rooms", "/api/v1/rooms/{id}", "/api/v1/rooms/recommend").hasAnyRole("USER", "ADMIN")
                .requestMatchers(POST, "/api/v1/hotels", "/api/v1/rooms").hasRole("ADMIN")
                .requestMatchers(PATCH, "/api/v1/hotels/{id}", "/api/v1/rooms/{id}").hasRole("ADMIN")
                .requestMatchers(DELETE, "/api/v1/hotels/{id}", "/api/v1/rooms/{id}").hasRole("ADMIN")
                .anyRequest().denyAll())
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.decoder(decoder)
                    .jwtAuthenticationConverter(authenticationConverter("roles", "ROLE_")))
                .authenticationEntryPoint(problemHandler)
                .accessDeniedHandler(problemHandler))
            .build();
    }

    private static HttpSecurity configureStatelessSecurity(HttpSecurity http, SecurityExceptionHandler problemHandler) {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(problemHandler)
                .accessDeniedHandler(problemHandler));
    }

    private static JwtAuthenticationConverter authenticationConverter(String claim, String prefix) {
        final var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(claim);
        authorities.setAuthorityPrefix(prefix);

        final var authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(authorities);
        return authentication;
    }
}
