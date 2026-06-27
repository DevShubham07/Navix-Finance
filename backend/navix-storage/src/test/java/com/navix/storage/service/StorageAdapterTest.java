package com.navix.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.storage.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the storage seam: the deterministic application-key pattern produced by
 * {@link DocumentStorageService#buildApplicationKey} (no S3 contact, so null clients are fine)
 * and the pass-through delegation of {@link StorageAdapter} onto the service.
 */
@ExtendWith(MockitoExtension.class)
class StorageAdapterTest {

    @Mock
    private DocumentStorageService storage;

    @Test
    void buildApplicationKey_followsDeterministicPattern() {
        // buildApplicationKey never touches S3 → the S3 clients can be null.
        DocumentStorageService service = new DocumentStorageService(
                null, null, new StorageProperties("bucket", 900, null));

        String key = service.buildApplicationKey(1L, "AADHAAR", "pdf");

        assertThat(key).matches("applications/1/aadhaar/\\d+\\.pdf");
    }

    @Test
    void adapter_delegatesPresignUpload() {
        StorageAdapter adapter = new StorageAdapter(storage);
        when(storage.presignUpload("key-1", "application/pdf")).thenReturn("https://s3/upload");

        String url = adapter.presignUpload("key-1", "application/pdf");

        assertThat(url).isEqualTo("https://s3/upload");
        verify(storage).presignUpload("key-1", "application/pdf");
    }

    @Test
    void adapter_delegatesPresignDownload() {
        StorageAdapter adapter = new StorageAdapter(storage);
        when(storage.presignDownload("key-2")).thenReturn("https://s3/download");

        String url = adapter.presignDownload("key-2");

        assertThat(url).isEqualTo("https://s3/download");
        verify(storage).presignDownload("key-2");
    }

    @Test
    void adapter_delegatesExists() {
        StorageAdapter adapter = new StorageAdapter(storage);
        when(storage.exists("key-3")).thenReturn(true);

        assertThat(adapter.exists("key-3")).isTrue();
        verify(storage).exists("key-3");
    }
}
