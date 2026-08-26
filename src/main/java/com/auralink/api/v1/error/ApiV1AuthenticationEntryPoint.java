package com.auralink.api.v1.error;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Authentication failure response used only for new {@code /api/v1} routes.
 * Legacy endpoints retain their existing authentication envelope.
 */
@Component
@RequiredArgsConstructor
public class ApiV1AuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException) throws IOException {
        String correlationId = correlationId(request);
        ApiV1ErrorResponse body = new ApiV1ErrorResponse(
                Instant.now(),
                HttpStatus.UNAUTHORIZED.value(),
                ApiErrorCode.UNAUTHORIZED.name(),
                "需要身份验证",
                request.getRequestURI(),
                correlationId,
                Map.of(),
                List.of());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setHeader(CORRELATION_HEADER, correlationId);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private String correlationId(HttpServletRequest request) {
        String supplied = request.getHeader(CORRELATION_HEADER);
        if (supplied != null && SAFE_CORRELATION_ID.matcher(supplied).matches()) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }
}
