package com.loanlens.exception;

import java.util.Map;

/** The request is well-formed but violates a business rule (HTTP 422). */
public class BusinessRuleException extends RuntimeException {

    private final transient Map<String, String> fieldErrors;

    public BusinessRuleException(String message) {
        this(message, Map.of());
    }

    public BusinessRuleException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
