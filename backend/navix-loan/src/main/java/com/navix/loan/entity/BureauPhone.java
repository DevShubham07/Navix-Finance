package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A 10-digit mobile a bureau report lists for an application (V80). Derived from the BUREAU raw response. */
@Entity
@Table(name = "bureau_phone")
@Getter
@Setter
@NoArgsConstructor
public class BureauPhone extends BaseAuditEntity {

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "mobile", nullable = false, length = 10)
    private String mobile;

    /** CRIF | EXPERIAN. */
    @Column(name = "source", length = 10)
    private String source;

    @Column(name = "reported_date")
    private LocalDate reportedDate;

    /** The report was flagged as possibly another person's: kept, but never used for matching. */
    @Column(name = "identity_mismatch", nullable = false)
    private boolean identityMismatch;

    @Column(name = "pulled_at")
    private Instant pulledAt;
}
