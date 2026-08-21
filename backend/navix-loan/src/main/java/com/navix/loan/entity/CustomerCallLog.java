package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A staff call log on a customer (type / outcome / optional callback date / notes). Who/when come
 * from {@link BaseAuditEntity}. Append-only; folded into the unified activity timeline as type CALL.
 */
@Entity
@Table(name = "customer_call_log")
@Getter
@Setter
@NoArgsConstructor
public class CustomerCallLog extends BaseAuditEntity {

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** The loan this call was about, if any (must belong to {@link #customerId}); null = customer-level. */
    @Column(name = "loan_id")
    private Long loanId;

    /** OUTBOUND | INBOUND | MISSED */
    @Column(name = "call_type", nullable = false, length = 24)
    private String callType;

    /** CONNECTED | NO_ANSWER | CALLBACK | REFUSED | WRONG_NUMBER */
    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    @Column(name = "callback_on")
    private LocalDate callbackOn;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;
}
