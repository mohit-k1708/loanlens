package com.loanlens.exception;

import java.util.List;

/**
 * Required inputs for a calculation are missing (HTTP 422). Missing inputs are reported, never replaced by zero.
 */
public class MissingInputsException extends RuntimeException {

    private final transient List<String> missingInputs;

    public MissingInputsException(String message, List<String> missingInputs) {
        super(message);
        this.missingInputs = List.copyOf(missingInputs);
    }

    public List<String> getMissingInputs() {
        return missingInputs;
    }
}
