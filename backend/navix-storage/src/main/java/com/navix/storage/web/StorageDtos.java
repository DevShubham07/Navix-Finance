package com.navix.storage.web;

import com.navix.storage.config.StorageCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request/response payloads for presigned-URL issuance.
 */
public final class StorageDtos {

    private StorageDtos() {
    }

    /** Ask for a presigned upload URL for a categorised document. */
    public record PresignUploadRequest(
            @NotNull StorageCategory category,
            @NotBlank String filename,
            @NotBlank String contentType
    ) {
    }

    /** Returned presigned PUT URL plus the key the client must reference later. */
    public record PresignUploadResponse(
            String key,
            String url,
            String method,
            long expiresInSeconds
    ) {
    }

    /** Returned presigned GET URL for a stored key. */
    public record PresignDownloadResponse(
            String key,
            String url,
            long expiresInSeconds
    ) {
    }
}
