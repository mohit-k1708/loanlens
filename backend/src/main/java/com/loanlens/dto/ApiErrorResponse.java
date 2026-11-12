package com.loanlens.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Uniform error body. {@code message} and {@code fieldErrors} match what the React frontend already understands.
 * Never contains stack traces or internal details.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors,
        List<String> missingInputs,
        String errorId) {
}
