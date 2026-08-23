package com.navix.storage.service;

import com.navix.storage.config.StorageCategory;
import com.navix.storage.config.StorageProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Object storage for DhanBoost documents/images on S3.
 *
 * <p>Large files (selfies, KYC docs, signed loan PDFs, payment/collections
 * proofs) never transit the app: clients receive a short-lived <b>presigned
 * URL</b> and upload/download directly to/from S3. Objects are encrypted at rest
 * by the bucket's default SSE-KMS configuration; server-side {@link #store}
 * puts may additionally pin a KMS key.
 *
 * <p>{@code S3Client} and {@code S3Presigner} are auto-configured by Spring
 * Cloud AWS from the default credential provider chain (ECS task role in prod,
 * AWS profile locally) and the configured region.
 */
@Service
@RequiredArgsConstructor
public class DocumentStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties props;

    /**
     * Build a unique, collision-safe object key for a category + original filename.
     * e.g. {@code kyc/selfie/3f9c....-passport_front.jpg}
     */
    public String buildKey(StorageCategory category, String filename) {
        String safe = StringUtils.hasText(filename)
                ? filename.replaceAll("[^a-zA-Z0-9._-]", "_")
                : "file";
        return category.prefix() + "/" + UUID.randomUUID() + "-" + safe;
    }

    /**
     * Deterministic, organised key for an application artifact:
     * {@code applications/{applicationId}/{docType}/{epochMillis}.{ext}}. Used by the
     * onboarding verification flow; keeps {@link #buildKey} for repayment/collections.
     */
    public String buildApplicationKey(Long applicationId, String docType, String ext) {
        String type = StringUtils.hasText(docType)
                ? docType.toLowerCase().replaceAll("[^a-z0-9._-]", "_") : "document";
        String suffix = StringUtils.hasText(ext)
                ? ext.toLowerCase().replaceAll("[^a-z0-9]", "") : "bin";
        return "applications/" + applicationId + "/" + type + "/" + Instant.now().toEpochMilli() + "." + suffix;
    }

    /** Presigned URL for a direct browser PUT upload to the given key. */
    public String presignUpload(String key, String contentType) {
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(ttl())
                .putObjectRequest(objectRequest)
                .build();
        return s3Presigner.presignPutObject(presignRequest).url().toString();
    }

    /** Presigned URL for a direct GET download of the given key. */
    public String presignDownload(String key) {
        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl())
                .getObjectRequest(objectRequest)
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /** Server-side upload of bytes (e.g. an app-generated PDF). */
    public void store(String key, byte[] content, String contentType) {
        PutObjectRequest.Builder builder = PutObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .contentType(contentType);
        if (StringUtils.hasText(props.kmsKeyId())) {
            builder.serverSideEncryption(ServerSideEncryption.AWS_KMS)
                    .ssekmsKeyId(props.kmsKeyId());
        }
        s3Client.putObject(builder.build(), RequestBody.fromBytes(content));
    }

    /**
     * Hard ceiling on a {@link #storeFromUrl} download. Remote fetches now include a
     * multi-MB bureau report PDF alongside the existing DigiLocker/selfie payloads, and
     * the previous {@code ofByteArray()} handler had no limit at all — an oversized or
     * malicious response would be buffered whole into heap. 10 MB comfortably covers every
     * known payload (report PDFs, Aadhaar images) with headroom.
     */
    private static final long MAX_REMOTE_DOCUMENT_BYTES = 10L * 1024 * 1024;

    /**
     * Fetch the bytes at {@code sourceUrl} (e.g. a provider's short-lived presigned
     * URL) and store them at {@code key}. Bytes pass provider→app→S3 only — never to
     * the browser. Returns the stored key. Throws {@link IllegalStateException} on a
     * non-2xx fetch, an oversized response, or a transport error.
     */
    public String storeFromUrl(String key, String sourceUrl, String contentType) {
        try {
            HttpResponse<InputStream> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    // Presigned S3 URLs never redirect, but other providers (the bureau
                    // report host included) may 302 — NORMAL was previously NEVER, which
                    // surfaced as a confusing "source fetch failed: HTTP 302".
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(HttpRequest.newBuilder()
                                    .uri(URI.create(sourceUrl))
                                    .timeout(Duration.ofSeconds(30))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("source fetch failed: HTTP " + response.statusCode());
            }
            long declaredLength = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (declaredLength > MAX_REMOTE_DOCUMENT_BYTES) {
                throw new IllegalStateException("source fetch failed: response exceeds " + MAX_REMOTE_DOCUMENT_BYTES + " byte cap");
            }
            byte[] body = readCapped(response.body(), MAX_REMOTE_DOCUMENT_BYTES);
            String resolvedType = StringUtils.hasText(contentType)
                    ? contentType
                    : response.headers().firstValue("content-type").orElse("application/octet-stream");
            store(key, body, resolvedType);
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("source fetch failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("source fetch interrupted", e);
        }
    }

    /**
     * Read {@code in} fully, aborting once more than {@code maxBytes} have arrived —
     * {@code Content-Length} is optional and can lie, so the cap must also apply to the
     * bytes actually received, not just the declared header.
     */
    private static byte[] readCapped(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalStateException("source fetch failed: response exceeds " + maxBytes + " byte cap");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * Server-side download of the bytes at {@code key} — used only where the app itself needs the
     * content (e.g. attaching a signed PDF to an email). Throws {@link IllegalStateException} on a
     * missing key or transport error.
     */
    public byte[] fetch(String key) {
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(key)
                    .build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new IllegalStateException("object not found: " + key, e);
        }
    }

    /** Delete an object. */
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .build());
    }

    /** True if an object exists at the given key. */
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private Duration ttl() {
        long seconds = props.presignTtlSeconds() > 0 ? props.presignTtlSeconds() : 900;
        return Duration.ofSeconds(seconds);
    }
}
