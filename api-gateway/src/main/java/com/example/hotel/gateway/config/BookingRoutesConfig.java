package com.example.hotel.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.setPath;
import static org.springframework.cloud.gateway.server.mvc.filter.LoadBalancerFilterFunctions.lb;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

@Configuration(proxyBeanMethods = false)
class BookingRoutesConfig {

    @Bean
    private static RouterFunction<ServerResponse> bookingCompatibilityRoutes() {
        return route("booking-compatibility")
            .POST("/api/v1/booking", http())
            .GET("/api/v1/booking/{id:[0-9]+}", http())
            .DELETE("/api/v1/booking/{id:[0-9]+}", http())
            .before(BookingRoutesConfig::rewriteBookingAlias)
            .filter(lb("booking-service"))
            .build();
    }

    private static ServerRequest rewriteBookingAlias(ServerRequest request) {
        final var targetPath = request.path().replace("/api/v1/booking", "/api/v1/bookings");
        return setPath(targetPath).apply(request);
    }
}
