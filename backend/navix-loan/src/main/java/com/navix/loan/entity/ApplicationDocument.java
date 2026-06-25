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
 * salary slip, bank statement, …). Bytes are stored inline as {@code bytea} — demo-grade storage
 * with no external object store; at go-live this becomes an S3 key via navix-storage.
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

    @Column(name = "data", nullable = false, columnDefinition = "bytea")
    private byte[] data;
}
