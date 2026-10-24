package com.example.hotel.booking.controller.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Utf8SizeValidator implements ConstraintValidator<Utf8Size, String> {
    private int min;
    private int max;

    @Override
    public void initialize(Utf8Size constraint) {
        min = constraint.min();
        max = constraint.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        var byteLength = value.getBytes(UTF_8).length;
        return byteLength >= min
            && byteLength <= max;
    }
}
