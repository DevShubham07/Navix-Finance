package com.navix.loan.esigndoc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An admin-uploaded PDF being Aadhaar-eSigned as one Signzy contract (V101). */
@Entity
@Table(name = "esign_document")
@Getter
@Setter
@NoArgsConstructor
public class EsignDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "s3_object_key", nullable = false, length = 512)
    private String s3ObjectKey;

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Column(name = "contract_id", length = 100)
    private String contractId;

    /** SENT | COMPLETED | FAILED | EXPIRED. */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "signed_s3_object_key", length = 512)
    private String signedS3ObjectKey;

    @Column(name = "audit_certificate_url", length = 1000)
    private String auditCertificateUrl;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;
}
