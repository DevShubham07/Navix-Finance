package com.navix.iam.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Singleton holding the company payee shown to borrowers on the repay screen: the UPI id, bank
 * account details, and the S3 keys for an uploaded UPI QR image + payee account-info PDF.
 *
 * <p>Exactly one row exists (seeded by Flyway V18). Only ADMIN may edit it (enforced in
 * {@code PaymentSettingsService}). Who last edited it is captured via {@link BaseAuditEntity}.
 */
@Entity
@Table(name = "payment_settings")
@Getter
@Setter
@NoArgsConstructor
public class PaymentSettings extends BaseAuditEntity {

    /** UPI VPA the borrower pays to (e.g. {@code navix.collections@hdfcbank}). */
    @Column(name = "upi_id", length = 120)
    private String upiId;

    /** Bank account holder name. */
    @Column(name = "account_name", length = 160)
    private String accountName;

    /** Bank account number (display string; may contain spaces). */
    @Column(name = "account_number", length = 40)
    private String accountNumber;

    /** Bank IFSC code. */
    @Column(name = "ifsc", length = 20)
    private String ifsc;

    /** Bank name. */
    @Column(name = "bank_name", length = 120)
    private String bankName;

    /** S3 key for the uploaded UPI QR image (null until uploaded). */
    @Column(name = "qr_object_key", length = 512)
    private String qrObjectKey;

    /** S3 key for the uploaded payee account-info PDF (null until uploaded). */
    @Column(name = "account_info_object_key", length = 512)
    private String accountInfoObjectKey;
}
