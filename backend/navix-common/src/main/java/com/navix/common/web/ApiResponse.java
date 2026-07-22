package com.navix.common.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Standard success/response envelope returned by all DhanBoost REST endpoints.
 *
 * TODO: wire into controllers; consider a ResponseBodyAdvice to wrap automatically.
 *
 * @param <T> the payload type
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T data;
    private ApiError error;
    private Instant timestamp;

    public static <T> ApiResponse<T> ok(T data) {
        // TODO: optionally support a custom success message
        return new ApiResponse<>(true, "OK", data, null, Instant.now());
    }

    public static <T> ApiResponse<T> failure(ApiError error) {
        return new ApiResponse<>(false, error != null ? error.getMessage() : "ERROR",
                null, error, Instant.now());
    }
}
