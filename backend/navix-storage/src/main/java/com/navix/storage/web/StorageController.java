package com.navix.storage.web;

import com.navix.storage.config.StorageProperties;
import com.navix.storage.service.DocumentStorageService;
import com.navix.storage.web.StorageDtos.PresignDownloadResponse;
import com.navix.storage.web.StorageDtos.PresignUploadRequest;
import com.navix.storage.web.StorageDtos.PresignUploadResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues short-lived presigned URLs so the browser uploads/downloads documents
 * directly to/from S3 (the bytes never pass through this app).
 *
 * <p>TODO (security): require an authenticated session, and authorise that the
 * caller owns the resource the key belongs to before issuing a download URL —
 * keys must not be guessable across borrowers.
 */
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageController {

    private final DocumentStorageService storage;
    private final StorageProperties props;

    /** Get a presigned PUT URL for a new document; returns the key to persist. */
    @PostMapping("/presign-upload")
    public PresignUploadResponse presignUpload(@Valid @RequestBody PresignUploadRequest request) {
        String key = storage.buildKey(request.category(), request.filename());
        String url = storage.presignUpload(key, request.contentType());
        return new PresignUploadResponse(key, url, "PUT", props.presignTtlSeconds());
    }

    /** Get a presigned GET URL for an existing key. */
    @GetMapping("/presign-download")
    public PresignDownloadResponse presignDownload(@RequestParam("key") String key) {
        // TODO: authorise that the caller may read this key.
        String url = storage.presignDownload(key);
        return new PresignDownloadResponse(key, url, props.presignTtlSeconds());
    }
}
