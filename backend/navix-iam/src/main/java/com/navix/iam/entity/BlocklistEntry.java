package com.navix.iam.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.iam.domain.BlocklistType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A fraud / chargeback / fake-KYC blocklist entry. Screened at sign-up AND
 * before approval; a match stops the application.
 *
 * TODO: add a unique index on (type, value). Who added/removed it is captured
 * via {@link BaseAuditEntity}.
 */
@Entity
@Table(name = "blocklist_entry")
@Getter
@Setter
@NoArgsConstructor
public class BlocklistEntry extends BaseAuditEntity {

    /** Which identifier this entry blocks. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 24)
    private BlocklistType type;

    /** The blocked value (PAN, masked Aadhaar reference, phone, device id, bank account). */
    @Column(name = "value", nullable = false)
    private String value;

    /** Why this identifier was blocklisted. */
    @Column(name = "reason")
    private String reason;

    /** Whether the entry is currently active. */
    @Column(name = "active", nullable = false)
    private boolean active;
}
