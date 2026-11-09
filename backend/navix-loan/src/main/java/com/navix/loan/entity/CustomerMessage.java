package com.navix.loan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One admin send attempt on one channel (V95): SENT, FAILED or SKIPPED (with the reason). */
@Entity
@Table(name = "customer_message")
@Getter
@Setter
@NoArgsConstructor
public class CustomerMessage {

    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "loan_id")
    private Long loanId;

    @Column(name = "template_key", nullable = false, length = 64)
    private String templateKey;

    @Column(name = "provider_template", nullable = false, length = 128)
    private String providerTemplate;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "mobile", length = 20)
    private String mobile;

    /** The address, on an email row. */
    @Column(name = "email", length = 255)
    private String email;

    /** JSON array of the ordered parameter values (text). */
    @Column(name = "params", columnDefinition = "text")
    private String params;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "provider_ref", length = 200)
    private String providerRef;

    @Column(name = "discount_percent")
    private Short discountPercent;

    @Column(name = "repay_by")
    private LocalDate repayBy;

    @Column(name = "sent_by_staff_id")
    private Long sentByStaffId;

    @Column(name = "sent_by_name", length = 200)
    private String sentByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void stamp() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
