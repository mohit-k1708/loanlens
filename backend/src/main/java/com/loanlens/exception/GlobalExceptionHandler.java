package com.loanlens.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.loanlens.ai.RateLimitExceededException;
import com.loanlens.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every failure into a consistent JSON body with the right HTTP status.
 * Unexpected errors are logged on the server with an errorId and returned WITHOUT any internal detail.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req, Map.of(), List.of(), null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> noResource(NoResourceFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "No such endpoint.", req, Map.of(), List.of(), null);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiErrorResponse> conflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req, Map.of(), List.of(), null);
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ApiErrorResponse> badRequest(BadRequestException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req, ex.getFieldErrors(), List.of(), null);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<ApiErrorResponse> businessRule(BusinessRuleException ex, HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), req, ex.getFieldErrors(), List.of(), null);
    }

    @ExceptionHandler(MissingInputsException.class)
    ResponseEntity<ApiErrorResponse> missingInputs(MissingInputsException ex, HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), req, Map.of(), ex.getMissingInputs(), null);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ApiErrorResponse> rateLimited(RateLimitExceededException ex, HttpServletRequest req) {
        ResponseEntity<ApiErrorResponse> base = build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), req, Map.of(), List.of(), null);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", String.valueOf(ex.getRetryAfterSeconds())).body(base.getBody());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> invalidBody(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors().forEach(ge -> errors.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, "Validation failed. Please correct the highlighted fields.", req, errors, List.of(), null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> invalidParameters(ConstraintViolationException ex, HttpServletRequest req) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> {
            String path = v.getPropertyPath().toString();
            errors.putIfAbsent(path.substring(path.lastIndexOf('.') + 1), v.getMessage());
        });
        return build(HttpStatus.BAD_REQUEST, "Validation failed.", req, errors, List.of(), null);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiErrorResponse> invalidMethodParameters(HandlerMethodValidationException ex, HttpServletRequest req) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(r -> r.getResolvableErrors().forEach(e -> {
            String name = r.getMethodParameter().getParameterName();
            errors.putIfAbsent(name == null ? "parameter" : name, e.getDefaultMessage());
        }));
        return build(HttpStatus.BAD_REQUEST, "Validation failed.", req, errors, List.of(), null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof UnrecognizedPropertyException upe) {
            return build(HttpStatus.BAD_REQUEST, "Unknown field '" + upe.getPropertyName() + "'.", req,
                    Map.of(upe.getPropertyName(), "Unknown field."), List.of(), null);
        }
        if (cause instanceof InvalidFormatException ife && !ife.getPath().isEmpty()) {
            String field = ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            String message = ife.getTargetType().isEnum()
                    ? "Invalid value. Allowed values: " + Arrays.toString(ife.getTargetType().getEnumConstants()) + "."
                    : "Invalid value for this field.";
            return build(HttpStatus.BAD_REQUEST, "Validation failed. Please correct the highlighted fields.", req,
                    Map.of(field == null ? "body" : field, message), List.of(), null);
        }
        return build(HttpStatus.BAD_REQUEST, "The request body is missing or is not valid JSON.", req, Map.of(), List.of(), null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String name = ex.getName();
        Class<?> required = ex.getRequiredType();
        String message = required != null && required.isEnum()
                ? "Invalid value. Allowed values: " + Arrays.toString(required.getEnumConstants()) + "."
                : "Invalid value.";
        return build(HttpStatus.BAD_REQUEST, "Invalid value for '" + name + "'.", req, Map.of(name, message), List.of(), null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiErrorResponse> missingParameter(MissingServletRequestParameterException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Required parameter '" + ex.getParameterName() + "' is missing.", req,
                Map.of(ex.getParameterName(), "This parameter is required."), List.of(), null);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    ResponseEntity<ApiErrorResponse> invalidSort(PropertyReferenceException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Cannot sort by '" + ex.getPropertyName() + "'.", req,
                Map.of("sort", "Unknown sort property."), List.of(), null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "This HTTP method is not supported for this endpoint.", req, Map.of(), List.of(), null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> mediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send the request body as application/json.", req, Map.of(), List.of(), null);
    }

    /** A race between two requests hit a database unique/foreign-key constraint that the service checks could not see. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> integrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        String errorId = UUID.randomUUID().toString();
        log.warn("Data integrity violation [{}] on {} {}", errorId, req.getMethod(), req.getRequestURI());
        return build(HttpStatus.CONFLICT, "The request conflicts with existing data. Please refresh and try again.", req, Map.of(), List.of(), errorId);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unexpected(Exception ex, HttpServletRequest req) {
        String errorId = UUID.randomUUID().toString();
        // Full detail stays in the server log (correlated by errorId); the client never sees it.
        log.error("Unexpected error [{}] on {} {}", errorId, req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. Quote the errorId when reporting it.", req, Map.of(), List.of(), errorId);
    }

    private static ResponseEntity<ApiErrorResponse> build(HttpStatus status, String message, HttpServletRequest req,
                                                          Map<String, String> fieldErrors, List<String> missing, String errorId) {
        ApiErrorResponse body = new ApiErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message,
                req.getRequestURI(), fieldErrors, missing, errorId);
        return ResponseEntity.status(status).body(body);
    }
}
