package com.auralink.api.v1.error;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Provider-independent error envelope for new /api/v1 endpoints.
 * It intentionally has no stack-trace, exception-class, or server-path field.
 */
public record ApiV1ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId,
        Map<String, String> validationErrors,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ApiViolationDetail> violations) {

    public ApiV1ErrorResponse {
        validationErrors = Map.copyOf(validationErrors);
        violations = List.copyOf(violations);
    }
}
