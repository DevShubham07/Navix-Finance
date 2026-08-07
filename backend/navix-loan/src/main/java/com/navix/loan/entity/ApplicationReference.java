package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A contact the borrower names on the Phase-3 references screen (V46; revamp.md decision 39).
 *
 * <p>Capture only. Despite the screen's "Refer &amp; Earn" heading these rows are deliberately
 * <b>not</b> wired to the referral rewards program — that keys on {@code referral_code} and a
 * redemption at signup, not on a name and number typed here.
 */
@Entity
@Table(name = "application_reference")
@Getter
@Setter
@NoArgsConstructor
public class ApplicationReference extends BaseAuditEntity {

    /** The relations the form offers, in the order they're listed. */
    public static final java.util.List<String> RELATIONS = java.util.List.of(
            "PARENT", "SPOUSE", "SIBLING", "RELATIVE", "FRIEND", "COLLEAGUE", "MANAGER", "NEIGHBOUR");

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Position on the form (1 or 2) — unique per application, so a re-submit updates in place. */
    @Column(name = "slot", nullable = false)
    private Short slot;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "mobile", nullable = false, length = 15)
    private String mobile;

    @Column(name = "relation", nullable = false, length = 32)
    private String relation;
}
