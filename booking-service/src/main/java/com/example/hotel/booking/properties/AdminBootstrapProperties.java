package com.example.hotel.booking.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("security.bootstrap-admin")
public record AdminBootstrapProperties(
    @DefaultValue("true") boolean enabled,
    String username,
    String password
) {

    @Override
    public String toString() {
        return "AdminBootstrapProperties[enabled=" + enabled + "]";
    }
}
