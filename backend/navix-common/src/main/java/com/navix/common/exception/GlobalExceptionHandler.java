package com.navix.common.exception;

import com.navix.common.web.ApiError;
import com.navix.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Central translation of exceptions into the standard {@link ApiResponse} envelope.
 *
 * <p>Every handler also logs once, so a failing request is visible in CloudWatch (not just in the
 * HTTP body the client receives). Expected/business rejections log at WARN with their code and the
 * request path (no stack trace); only unexpected errors log at ERROR with the full stack. PII is kept
 * out of the logs: we log the path (not the query string), error codes, and field NAMES only — never
 * the rejected values, request body, or any identifier.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex,
                                                            HttpServletRequest request) {
        log.warn("not found path={} msg={}", request.getRequestURI(), ex.getMessage());
        ApiError error = ApiError.builder()
                .code("NOT_FOUND")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(error));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex,
                                                           HttpServletRequest request) {
        // Business rejection (e.g. SOD_VIOLATION, ILLEGAL_TRANSITION, FORBIDDEN_ROLE, INVALID_OTP) —
        // expected; WARN with the code, no stack trace.
        log.warn("business rejection code={} path={} msg={}",
                ex.getCode(), request.getRequestURI(), ex.getMessage());
        ApiError error = ApiError.builder()
                .code(ex.getCode())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.failure(error));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex,
                                                             HttpServletRequest request) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        // Log field NAMES only — never the rejected values (they may be PII).
        log.warn("validation failed path={} fields={}", request.getRequestURI(), fieldErrors.keySet());
        ApiError error = ApiError.builder()
                .code("VALIDATION_ERROR")
                .message("Request validation failed")
                .path(request.getRequestURI())
                .fieldErrors(fieldErrors)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(error));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex,
                                                          HttpServletRequest request) {
        // Unexpected — ERROR with the full stack trace for debugging (the client still gets a generic
        // message). The throwable is passed as the last arg so logback prints the stack.
        log.error("unexpected error path={}", request.getRequestURI(), ex);
        ApiError error = ApiError.builder()
                .code("INTERNAL_ERROR")
                .message("An unexpected error occurred")
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(error));
    }
}
