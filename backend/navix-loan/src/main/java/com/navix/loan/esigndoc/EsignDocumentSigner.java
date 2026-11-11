package com.navix.loan.esigndoc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One signatory on an {@link EsignDocument}. */
@Entity
@Table(name = "esign_document_signer")
@Getter
@Setter
@NoArgsConstructor
public class EsignDocumentSigner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "signer_order", nullable = false)
    private int signerOrder;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 255)
    private String email;

    @Column(name = "is_self", nullable = false)
    private boolean self;

    /** Legacy (V101 rows only): All | First | Last | a list such as 1,3. Null for box-placed signers. */
    @Column(length = 100)
    private String pages;

    /** Legacy (V101 rows only): one of nine presets. Null for box-placed signers. */
    @Column(length = 20)
    private String position;

    /** JSON array of {page,x,y,width,height}, PDF points, top-left origin. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "boxes", columnDefinition = "jsonb")
    private String boxes;

    @Column(name = "signer_id", length = 100)
    private String signerId;

    @Column(name = "sign_url", length = 1000)
    private String signUrl;

    /** PENDING | SIGNED | FAILED. */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "signed_at")
    private Instant signedAt;

    /** NOT_SENT | SENT | FAILED | SKIPPED. */
    @Column(name = "email_status", nullable = false, length = 20)
    private String emailStatus = "NOT_SENT";

    @Column(name = "email_sent_at")
    private Instant emailSentAt;
}
