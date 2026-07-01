package com.navix.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A one-time password-reset token. We persist only the <b>SHA-256 hash</b> of the token — the raw
 * token travels solely in the emailed link, so a database leak can't be replayed. Single-use
 * ({@code usedAt}) and short-lived ({@code expiresAt}); {@code subjectType} keeps a borrower token
 * from being redeemed on the staff reset page and vice-versa.
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    /** BORROWER | STAFF. */
    @Column(name = "subject_type", nullable = false, length = 10)
    private String subjectType;

    /** customerId (borrower) or staffId (staff). */
    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
