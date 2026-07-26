package com.navix.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Records which mobile first claimed each mobile-derived {@code customerId} — the collision guard for
 * {@code AuthController.deriveCustomerId}, which keeps only the last 7 digits. Two mobiles sharing
 * those 7 digits derive the SAME id and would otherwise land silently in one account.
 *
 * <p>A guard, not a fix: the derivation is unchanged, and the second mobile fails loudly at login
 * instead of merging. The real fix is a surrogate customer id.
 *
 * <p>Distinct from {@code customer_profile.mobile}, which is deliberately non-unique (V23) because a
 * returning borrower has one profile row per application. The grain here is one row per BORROWER.
 */
@Entity
@Table(name = "borrower_mobile")
@Getter
@Setter
@NoArgsConstructor
public class BorrowerMobile {

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "mobile", nullable = false, unique = true, length = 10)
    private String mobile;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
