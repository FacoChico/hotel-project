package com.example.hotel.booking.config;

import com.example.hotel.booking.security.SecurityExceptionHandler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import static jakarta.servlet.DispatcherType.ERROR;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

@EnableMethodSecurity
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityExceptionHandler problemHandler) {
        final var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        final var authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(authorities);

        return http
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(access -> access
                .dispatcherTypeMatchers(ERROR).permitAll()
                .requestMatchers(POST, "/api/v1/users/register", "/api/v1/users/auth").permitAll()
                .requestMatchers(GET, "/actuator/health", "/v3/api-docs", "/v3/api-docs.yaml",
                    "/v3/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                .requestMatchers(GET, "/actuator/info").hasRole("ADMIN")
                .requestMatchers(GET, "/api/v1/users", "/api/v1/users/{id}").hasRole("ADMIN")
                .requestMatchers(POST, "/api/v1/users").hasRole("ADMIN")
                .requestMatchers(PATCH, "/api/v1/users/{id}").hasRole("ADMIN")
                .requestMatchers(DELETE, "/api/v1/users/{id}").hasRole("ADMIN")
                .requestMatchers(GET, "/api/v1/bookings", "/api/v1/bookings/{id}", "/api/v1/bookings/recommendations").hasRole("USER")
                .requestMatchers(POST, "/api/v1/bookings").hasRole("USER")
                .requestMatchers(DELETE, "/api/v1/bookings/{id}").hasRole("USER")
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(problemHandler)
                .accessDeniedHandler(problemHandler))
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.jwtAuthenticationConverter(authentication))
                .authenticationEntryPoint(problemHandler)
                .accessDeniedHandler(problemHandler))
            .build();
    }
}
