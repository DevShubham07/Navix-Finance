package com.navix.storage.service;

import com.navix.common.storage.DocumentStoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Adapter exposing {@link DocumentStorageService} as the {@link DocumentStoragePort}
 * consumed by the loan aggregate. Wired by component scan from {@code navix-app}
 * (same pattern as {@code LoanDirectoryAdapter}); adds no new Maven edge.
 */
@Component
@RequiredArgsConstructor
public class StorageAdapter implements DocumentStoragePort {

    private final DocumentStorageService storage;

    @Override
    public String buildApplicationKey(Long applicationId, String docType, String ext) {
        return storage.buildApplicationKey(applicationId, docType, ext);
    }

    @Override
    public String presignUpload(String key, String contentType) {
        return storage.presignUpload(key, contentType);
    }

    @Override
    public String presignDownload(String key) {
        return storage.presignDownload(key);
    }

    @Override
    public void store(String key, byte[] content, String contentType) {
        storage.store(key, content, contentType);
    }

    @Override
    public String storeFromUrl(String key, String sourceUrl, String contentType) {
        return storage.storeFromUrl(key, sourceUrl, contentType);
    }

    @Override
    public boolean exists(String key) {
        return storage.exists(key);
    }
}
