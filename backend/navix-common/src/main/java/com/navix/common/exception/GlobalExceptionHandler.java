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

    /**
     * An unmapped URL is a 404, not a 500.
     *
     * <p>Without this, both of Spring's "no handler" exceptions fell through to
     * {@link #handleGeneric} — so a typo'd path, a client calling a retired endpoint, or a scanner
     * probing the API all answered {@code 500 INTERNAL_ERROR} and logged a full stack trace at
     * ERROR. That is wrong twice over: it tells the caller the server broke when the request was
     * simply wrong, and it fills the error log (and any alerting off it) with noise nobody can act
     * on.
     */
    @ExceptionHandler({
            org.springframework.web.servlet.NoHandlerFoundException.class,
            org.springframework.web.servlet.resource.NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(Exception ex, HttpServletRequest request) {
        log.warn("no handler path={} method={}", request.getRequestURI(), request.getMethod());
        ApiError error = ApiError.builder()
                .code("NOT_FOUND")
                .message("No such endpoint")
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(error));
    }

    /**
     * A body the server can't parse is the caller's mistake — 400, not 500.
     *
     * <p>Malformed JSON, a truncated payload, or a wrong-typed field all raise this. Letting it fall
     * through to {@link #handleGeneric} reported "an unexpected error occurred" for a request that
     * was simply invalid, and logged a stack trace at ERROR for it. The parser's own message is not
     * echoed back — it can quote the offending body, which may carry PII.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        log.warn("unreadable request body path={}", request.getRequestURI());
        ApiError error = ApiError.builder()
                .code("MALFORMED_REQUEST")
                .message("The request body could not be read. Send valid JSON.")
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(error));
    }

    /** The path exists but not for this verb — 405, again not a 500. */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        log.warn("method not allowed path={} method={}", request.getRequestURI(), request.getMethod());
        ApiError error = ApiError.builder()
                .code("METHOD_NOT_ALLOWED")
                .message(request.getMethod() + " is not supported on this endpoint")
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.failure(error));
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
