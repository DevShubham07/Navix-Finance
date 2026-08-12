package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Published once the borrower's sanction letter is signed (Aadhaar e-sign or the manual fallback) and
 * the {@code SIGNED_AGREEMENT} document is stored. Drives an email of the signed PDF as an attachment
 * (IN_APP + EMAIL only, no SMS). {@code s3ObjectKey} is the storage key for the signed copy — carried
 * here (rather than resolved downstream from {@code signedDocumentId}) so the notification module can
 * fetch the bytes via {@code DocumentStoragePort} without depending on the loan module's document
 * repository.
 */
public record SanctionLetterSignedEvent(
        Long customerId,
        Long applicationId,
        Long signedDocumentId,
        String s3ObjectKey,
        Instant at) {
}
