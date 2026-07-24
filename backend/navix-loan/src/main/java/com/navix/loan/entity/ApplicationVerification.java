package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One external verification step's result, hanging off a {@code loan_application}
 * (no separate KYC aggregate). Rows accumulate while the application is DRAFT; the
 * unique {@code (application_id, check_type)} is the idempotency key — a step that
 * already PASSed is not re-called.
 *
 * <p>{@code rawResponse}/{@code derived} are {@code jsonb} (mapped natively on
 * Hibernate 6). PII is minimised before persisting raw provider envelopes.
 */
@Entity
@Table(name = "application_verification")
@Getter
@Setter
@NoArgsConstructor
public class ApplicationVerification extends BaseAuditEntity {

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    /** PAN, EMAIL, ADDRESS, DIGILOCKER, AADHAAR, BUREAU, SALARY, PENNY_DROP, SELFIE, AGREEMENT. */
    @Column(name = "check_type", nullable = false, length = 40)
    private String checkType;

    /** PASS, FAIL, REVIEW, PENDING. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** FINTRIX, DIGILOCKER, EXPERIAN, CRIF, DhanBoost (internal). */
    @Column(name = "provider", length = 40)
    private String provider;

    @Column(name = "provider_txn_id", length = 120)
    private String providerTxnId;

    @Column(name = "client_ref_num", length = 120)
    private String clientRefNum;

    /** 0..1 name-match score where this step compares a name (penny-drop / aadhaar). */
    @Column(name = "name_match")
    private Double nameMatch;

    /** Numeric signal where relevant (e.g. bureau score, liveness confidence × 100). */
    @Column(name = "score")
    private Long score;

    /** S3 key for any document this step produced (e.g. the Aadhaar PDF). */
    @Column(name = "s3_object_key", length = 512)
    private String s3ObjectKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private String rawResponse;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "derived", columnDefinition = "jsonb")
    private String derived;

    @Column(name = "message", length = 1000)
    private String message;
}
