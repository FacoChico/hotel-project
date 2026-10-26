package com.example.hotel.booking.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@Configuration(proxyBeanMethods = false)
@ConfigurationPropertiesScan("com.example.hotel.booking")
class PropertiesConfig {
}
