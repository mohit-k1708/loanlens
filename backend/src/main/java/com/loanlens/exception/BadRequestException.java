package com.loanlens.exception;

import java.util.Map;

/** The request itself is malformed or invalid (HTTP 400). */
public class BadRequestException extends RuntimeException {

    private final transient Map<String, String> fieldErrors;

    public BadRequestException(String message) {
        this(message, Map.of());
    }

    public BadRequestException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
