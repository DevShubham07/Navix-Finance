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
 * A fraud / chargeback / fake-KYC blocklist entry. A customer matching any active entry cannot
 * start a new application or reborrow ({@code ApplicationFlowService.assertNotBlocked}). The value is
 * stored normalised ({@code BlocklistKey.normalize}); (type, value) is unique (V103). Who added /
 * removed it is captured via {@link BaseAuditEntity}.
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

    /**
     * The customer this entry was written for by "Block customer" (null for an entry an ADMIN
     * typed on the blocklist page). Removing the last such entry lifts that customer's staff block.
     */
    @Column(name = "customer_id")
    private Long customerId;
}
