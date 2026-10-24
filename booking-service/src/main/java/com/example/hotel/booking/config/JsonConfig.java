package com.example.hotel.booking.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static tools.jackson.databind.cfg.CoercionAction.Fail;
import static tools.jackson.databind.cfg.CoercionInputShape.Boolean;
import static tools.jackson.databind.cfg.CoercionInputShape.Float;
import static tools.jackson.databind.cfg.CoercionInputShape.Integer;
import static tools.jackson.databind.type.LogicalType.Textual;

@Configuration(proxyBeanMethods = false)
class JsonConfig {

    @Bean
    JsonMapperBuilderCustomizer strictStringValues() {
        return builder -> builder.withCoercionConfig(
            Textual,
            coercion -> coercion
                .setCoercion(Integer, Fail)
                .setCoercion(Float, Fail)
                .setCoercion(Boolean, Fail)
        );
    }
}
