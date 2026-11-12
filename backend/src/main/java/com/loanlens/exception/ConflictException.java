package com.loanlens.exception;

/** The request conflicts with existing data, e.g. a duplicate code or a reporting period that already has a record (HTTP 409). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
