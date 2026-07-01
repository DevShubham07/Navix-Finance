package com.navix.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A borrower's password credential — the first durable per-customer row in the live app (borrowers
 * were OTP-only before). Keyed by the same mobile-derived {@code customerId} that is the JWT subject,
 * so a returning borrower deterministically resolves to their credential.
 */
@Entity
@Table(name = "borrower_credential")
@Getter
@Setter
@NoArgsConstructor
public class BorrowerCredential {

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
