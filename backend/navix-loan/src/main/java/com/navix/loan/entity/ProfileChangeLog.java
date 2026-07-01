package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One audited change to an customer's profile/salary data: the field, its previous value and its new
 * value. Who changed it and when come from {@link BaseAuditEntity} ({@code created_by} = the editor's
 * name, {@code created_at} = the timestamp). Append-only — written by the profile-edit paths so a
 * salary/employer/address change leaves a previous→new trail (Phase 2.1).
 */
@Entity
@Table(name = "profile_change_log")
@Getter
@Setter
@NoArgsConstructor
public class ProfileChangeLog extends BaseAuditEntity {

    /** The customer whose profile was edited. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** The application whose 1:1 profile snapshot was edited (the latest profile), if known. */
    @Column(name = "application_id")
    private Long applicationId;

    /** The profile field that changed (e.g. {@code monthlySalaryPaise}, {@code address}). */
    @Column(name = "field", nullable = false, length = 64)
    private String field;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;
}
