package com.example.hotel.hotel.controller.serialization;

import java.time.LocalDate;

import tools.jackson.databind.util.StdConverter;

public final class IsoDateConverter extends StdConverter<String, LocalDate> {

    @Override
    public LocalDate convert(String value) {
        return LocalDate.parse(value);
    }
}
