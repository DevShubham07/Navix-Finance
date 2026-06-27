package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A KYC/supporting document a borrower uploaded for an application (PAN card, Aadhaar, selfie,
 * salary slip, bank statement, …). Stored either as an {@code s3_object_key} (the live path) or,
 * for pre-existing rows, inline as {@code bytea}. Invariant (enforced in the service): exactly one
 * of {@code data} / {@code s3ObjectKey} is set.
 */
@Entity
@Table(name = "application_document")
@Getter
@Setter
@NoArgsConstructor
public class ApplicationDocument extends BaseAuditEntity {

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    /** Free-form category, e.g. PAN, AADHAAR, SELFIE, SALARY_SLIP, BANK_STATEMENT. */
    @Column(name = "doc_type", nullable = false, length = 64)
    private String docType;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** Inline bytes (legacy/demo path). Nullable since V15 — S3-backed rows use {@link #s3ObjectKey}. */
    @Column(name = "data", columnDefinition = "bytea")
    private byte[] data;

    /** S3 object key (the live path). Exactly one of {@link #data} / this is set. */
    @Column(name = "s3_object_key", length = 512)
    private String s3ObjectKey;
}
