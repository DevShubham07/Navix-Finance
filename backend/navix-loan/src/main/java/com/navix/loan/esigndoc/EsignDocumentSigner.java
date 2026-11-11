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

    /** The original choice made by the admin: All | First | Last | a list such as 1,3. */
    @Column(nullable = false, length = 100)
    private String pages;

    @Column(nullable = false, length = 20)
    private String position;

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
