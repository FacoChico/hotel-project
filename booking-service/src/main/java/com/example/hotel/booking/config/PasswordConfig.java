package com.example.hotel.booking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder;

@Configuration(proxyBeanMethods = false)
class PasswordConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return createDelegatingPasswordEncoder();
    }
}
