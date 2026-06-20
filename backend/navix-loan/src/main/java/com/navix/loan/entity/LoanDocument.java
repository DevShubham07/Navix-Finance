package com.navix.loan.entity;

import com.navix.loan.domain.LoanDocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A document attached to a loan (agreement, sanction letter, KFS).
 *
 * TODO: store object-storage key / hash and signed timestamp once the
 * document-signing flow is wired.
 */
@Entity
@Table(name = "loan_document")
@Getter
@Setter
@NoArgsConstructor
public class LoanDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning loan reference. */
    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    /** Document kind. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private LoanDocumentType type;

    /** Storage location / URL of the document. */
    @Column(name = "storage_uri")
    private String storageUri;
}
