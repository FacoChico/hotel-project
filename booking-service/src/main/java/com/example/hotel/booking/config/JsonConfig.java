package com.example.hotel.booking.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

import static tools.jackson.databind.cfg.CoercionAction.Fail;
import static tools.jackson.databind.cfg.CoercionInputShape.Boolean;
import static tools.jackson.databind.cfg.CoercionInputShape.Float;
import static tools.jackson.databind.cfg.CoercionInputShape.Integer;
import static tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS;
import static tools.jackson.databind.type.LogicalType.Textual;

@Configuration(proxyBeanMethods = false)
class JsonConfig {

    @Bean
    private static JsonMapperBuilderCustomizer strictStringValues() {
        return builder -> builder.withCoercionConfig(
            Textual,
            coercion -> coercion
                .setCoercion(Integer, Fail)
                .setCoercion(Float, Fail)
                .setCoercion(Boolean, Fail)
        ).withCoercionConfig(
            LogicalType.Integer,
            coercion -> coercion
                .setCoercion(Float, Fail)
                .setCoercion(Boolean, Fail)
                .setCoercion(CoercionInputShape.String, Fail)
                .setCoercion(CoercionInputShape.EmptyString, Fail)
        ).withCoercionConfig(
            LogicalType.Boolean,
            coercion -> coercion
                .setCoercion(Integer, Fail)
                .setCoercion(Float, Fail)
                .setCoercion(CoercionInputShape.String, Fail)
                .setCoercion(CoercionInputShape.EmptyString, Fail)
        ).enable(FAIL_ON_NUMBERS_FOR_ENUMS);
    }
}
