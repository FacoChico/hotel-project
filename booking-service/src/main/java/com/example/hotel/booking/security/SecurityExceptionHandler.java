package com.example.hotel.booking.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import tools.jackson.databind.json.JsonMapper;

import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

@Component
@RequiredArgsConstructor
public class SecurityExceptionHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final JsonMapper mapper;

    @Override
    public void commence(@NonNull HttpServletRequest request,
                         @NonNull HttpServletResponse response,
                         @NonNull AuthenticationException exception) throws IOException {
        response.setHeader(WWW_AUTHENTICATE, "Bearer");
        writeExceptionDetails(request, response, UNAUTHORIZED, "Authentication is required");
    }

    @Override
    public void handle(@NonNull HttpServletRequest request,
                       @NonNull HttpServletResponse response,
                       @NonNull AccessDeniedException exception) throws IOException {
        writeExceptionDetails(request, response, FORBIDDEN, "Access is denied");
    }

    private void writeExceptionDetails(HttpServletRequest request,
                                       HttpServletResponse response,
                                       HttpStatus status,
                                       String detail) throws IOException {
        final var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", status.name());

        response.setStatus(status.value());
        response.setContentType(APPLICATION_PROBLEM_JSON_VALUE);

        mapper.writeValue(response.getOutputStream(), problem);
    }
}
