package com.navix.storage.web;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.storage.config.StorageCategory;
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
 * <p>Both routes need a signed-in caller (SecurityConfig no longer leaves {@code /api/storage/**}
 * open), and each is narrowed to the callers that actually use it. Borrower KYC uploads do not come
 * through here at all: they use the application-scoped
 * {@code /api/applications/{id}/verify/presign-upload}, which checks ownership.
 */
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageController {

    /** The one prefix a generic download may name: the staff lead-import screen's "download file". */
    static final String DOWNLOADABLE_PREFIX = StorageCategory.LEAD_IMPORT.prefix() + "/";

    private final DocumentStorageService storage;
    private final StorageProperties props;

    /** Get a presigned PUT URL for a new document; returns the key to persist. */
    @PostMapping("/presign-upload")
    public PresignUploadResponse presignUpload(@Valid @RequestBody PresignUploadRequest request) {
        requireMayUpload(request.category(), ActorContext.get().role());
        String key = storage.buildKey(request.category(), request.filename());
        String url = storage.presignUpload(key, request.contentType());
        return new PresignUploadResponse(key, url, "PUT", props.presignTtlSeconds());
    }

    /**
     * Get a presigned GET URL for an existing key. Only lead-import files are downloadable here, and
     * only by non-DSA staff — the same audience {@code LeadImportJobService} shows the key to. Every
     * other document is presigned server-side by the service that has already checked ownership.
     */
    @GetMapping("/presign-download")
    public PresignDownloadResponse presignDownload(@RequestParam("key") String key) {
        String role = ActorContext.get().role();
        if (!isStaff(role) || "DSA".equals(role)) {
            throw forbidden();
        }
        if (key == null || !key.startsWith(DOWNLOADABLE_PREFIX) || key.contains("..")) {
            throw new BusinessException("FORBIDDEN_KEY", "That file cannot be downloaded here");
        }
        String url = storage.presignDownload(key);
        return new PresignDownloadResponse(key, url, props.presignTtlSeconds());
    }

    /**
     * Who may mint an upload URL for each category — exactly today's callers. A repayment proof is
     * uploaded by a borrower on /repay or by an ADMIN logging a payment (the repay page can carry a
     * staff token when both cookies are present, so any non-DSA role may mint one; recording the
     * payment is guarded separately). A lead file is uploaded by any staff role, DSA included.
     * Payment-settings assets and expense receipts are ADMIN screens; the rest have no caller.
     */
    static void requireMayUpload(StorageCategory category, String role) {
        boolean allowed = switch (category) {
            case REPAYMENT_PROOF -> role != null && !"DSA".equals(role)
                    && (isStaff(role) || "BORROWER".equals(role));
            case LEAD_IMPORT -> isStaff(role);
            default -> "ADMIN".equals(role);
        };
        if (!allowed) {
            throw forbidden();
        }
    }

    private static boolean isStaff(String role) {
        return role != null && !"BORROWER".equals(role) && !"ANONYMOUS".equals(role) && !"SYSTEM".equals(role);
    }

    private static BusinessException forbidden() {
        return new BusinessException("FORBIDDEN_ROLE", "Your role cannot use this upload or download");
    }
}
