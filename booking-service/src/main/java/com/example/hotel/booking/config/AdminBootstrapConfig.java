package com.example.hotel.booking.config;

import com.example.hotel.booking.properties.AdminBootstrapProperties;
import com.example.hotel.booking.service.AdminBootstrapService;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AdminBootstrapConfig {

    @Bean
    @ConditionalOnProperty(name = "security.bootstrap-admin.enabled", matchIfMissing = true)
    private static ApplicationRunner adminBootstrapRunner(AdminBootstrapProperties properties,
                                                          AdminBootstrapService service) {
        return arguments -> service.initializeAdmin(properties);
    }
}
