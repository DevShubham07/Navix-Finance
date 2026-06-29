package com.navix.iam.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single company operating expense, tracked by an ADMIN (description, amount, payee, notes).
 * Money is integer paise. Who recorded it and when is captured via {@link BaseAuditEntity}
 * ({@code created_by} = the admin's name, {@code created_at} = the timestamp).
 */
@Entity
@Table(name = "company_expense")
@Getter
@Setter
@NoArgsConstructor
public class CompanyExpense extends BaseAuditEntity {

    /** What the spend was for. */
    @Column(name = "description", nullable = false, length = 300)
    private String description;

    /** Amount paid, in integer paise. */
    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    /** The payee — who/which entity was paid. */
    @Column(name = "paid_to", nullable = false, length = 200)
    private String paidTo;

    /** Optional free-text notes. */
    @Column(name = "notes", length = 1000)
    private String notes;

    /** The date the expense was incurred / paid. */
    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    /**
     * Optional S3 object key for an uploaded receipt/attachment (bill, invoice, payment screenshot).
     * Turned into a short-lived presigned download URL on read; the key itself is never returned.
     */
    @Column(name = "receipt_object_key", length = 512)
    private String receiptObjectKey;
}
