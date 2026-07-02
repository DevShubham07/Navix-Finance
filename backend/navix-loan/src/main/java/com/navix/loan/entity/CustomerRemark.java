package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A free-text staff remark on a customer, shown in the customer detail popup's "Remarks" tab and
 * folded into the unified activity timeline. Who/when come from {@link BaseAuditEntity}
 * ({@code created_by} = the author's name, {@code created_at} = the timestamp). Append-only.
 */
@Entity
@Table(name = "customer_remark")
@Getter
@Setter
@NoArgsConstructor
public class CustomerRemark extends BaseAuditEntity {

    /** The customer this remark is about. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;
}
