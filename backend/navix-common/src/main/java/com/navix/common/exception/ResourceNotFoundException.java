package com.navix.common.exception;

import lombok.Getter;

/**
 * Thrown when a requested resource (loan, customer, document, etc.) does not exist.
 *
 * TODO: use across repositories/services for consistent 404 handling.
 */
@Getter
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceType;
    private final String resourceId;

    public ResourceNotFoundException(String resourceType, String resourceId) {
        super("%s not found: %s".formatted(resourceType, resourceId));
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }
}
