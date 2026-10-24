package com.example.hotel.booking.config;

import com.example.hotel.booking.security.SecurityExceptionHandler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityExceptionHandler problemHandler) {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        var authentication = new JwtAuthenticationConverter();
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
                .requestMatchers(POST, "/api/users/register", "/api/users/auth").permitAll()
                .requestMatchers(GET, "/actuator/health").permitAll()
                .requestMatchers(GET, "/actuator/info").hasRole("ADMIN")
                .requestMatchers(GET, "/api/users", "/api/users/{id}").hasRole("ADMIN")
                .requestMatchers(POST, "/api/users").hasRole("ADMIN")
                .requestMatchers(PATCH, "/api/users/{id}").hasRole("ADMIN")
                .requestMatchers(DELETE, "/api/users/{id}").hasRole("ADMIN")
                .requestMatchers(GET, "/api/bookings", "/api/bookings/{id}").hasRole("USER")
                .requestMatchers(POST, "/api/bookings").hasRole("USER")
                .requestMatchers(DELETE, "/api/bookings/{id}").hasRole("USER")
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
