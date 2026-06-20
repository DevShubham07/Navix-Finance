package com.navix.common.web;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Structured error detail carried inside {@link ApiResponse} for failed requests.
 *
 * TODO: populate fieldErrors from validation failures in GlobalExceptionHandler.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiError {

    /** Machine-readable error code (e.g. BUSINESS_RULE_VIOLATION, NOT_FOUND). */
    private String code;

    /** Human-readable error message. */
    private String message;

    /** Request path that produced the error. */
    private String path;

    /** Per-field validation messages, keyed by field name. */
    private Map<String, String> fieldErrors;

    /** Additional contextual error details. */
    private List<String> details;
}
