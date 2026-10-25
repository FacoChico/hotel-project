package com.example.hotel.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.URI;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.setPath;
import static org.springframework.cloud.gateway.server.mvc.filter.LoadBalancerFilterFunctions.lb;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;

@Configuration(proxyBeanMethods = false)
class UserRoutesConfig {

    @Bean
    private static RouterFunction<ServerResponse> userCompatibilityRoutes() {
        return route("user-compatibility")
            .POST("/api/v1/user/register", http())
            .POST("/api/v1/user/auth", http())
            .POST("/api/v1/user", http())
            .PATCH("/api/v1/user", http())
            .DELETE("/api/v1/user", http())
            .filter(UserRoutesConfig::rewriteUserAlias)
            .filter(lb("booking-service"))
            .build();
    }

    private static ServerResponse rewriteUserAlias(ServerRequest request,
                                                   HandlerFunction<ServerResponse> next) throws Exception {
        final String targetPath;
        if (request.method() == POST) {
            targetPath = request.path().replace("/api/v1/user", "/api/v1/users");
        } else {
            final var ids = request.params().get("id");
            if (ids == null || ids.size() != 1) {
                return invalidUserId(request);
            }

            final long userId;
            try {
                userId = Long.parseLong(ids.getFirst());
            } catch (NumberFormatException exception) {
                return invalidUserId(request);
            }
            if (userId <= 0) {
                return invalidUserId(request);
            }
            targetPath = "/api/v1/users/" + userId;
        }
        return next.handle(setPath(targetPath).apply(request));
    }

    private static ServerResponse invalidUserId(ServerRequest request) {
        final var problem = ProblemDetail.forStatusAndDetail(
            BAD_REQUEST, "A single positive id query parameter is required");
        problem.setProperty("code", "INVALID_REQUEST_PARAMETERS");
        problem.setInstance(URI.create(request.path()));
        return ServerResponse.badRequest()
            .contentType(APPLICATION_PROBLEM_JSON)
            .body(problem);
    }
}
