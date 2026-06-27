package com.navix.common.storage;

/**
 * Port for object storage, implemented by {@code StorageAdapter} in
 * {@code navix-storage} (delegating to {@code DocumentStorageService}) and
 * consumed by the loan aggregate. Keeps the AWS S3 SDK off the loan classpath.
 *
 * <p>Large bytes never transit the app on download: callers hand the borrower a
 * short-lived <b>presigned URL</b>. Server-side ingest ({@link #store} /
 * {@link #storeFromUrl}) is used only where bytes are fetched provider→app→S3
 * (e.g. the DigiLocker Aadhaar PDF), never browser→app.
 */
public interface DocumentStoragePort {

    /**
     * Deterministic, collision-safe key for an application artifact:
     * {@code applications/{applicationId}/{docType}/{timestamp}.{ext}}.
     */
    String buildApplicationKey(Long applicationId, String docType, String ext);

    /** Presigned URL for a direct browser PUT upload to {@code key}. */
    String presignUpload(String key, String contentType);

    /** Presigned URL for a direct GET download of {@code key} (short TTL). */
    String presignDownload(String key);

    /** Server-side upload of bytes already in hand. */
    void store(String key, byte[] content, String contentType);

    /**
     * Fetch the bytes at {@code sourceUrl} (e.g. a provider's 10-minute presigned
     * URL) and store them at {@code key}. Bytes pass app→S3 only, never to the
     * browser. Returns the stored key.
     */
    String storeFromUrl(String key, String sourceUrl, String contentType);

    /** True if an object exists at {@code key}. */
    boolean exists(String key);
}
