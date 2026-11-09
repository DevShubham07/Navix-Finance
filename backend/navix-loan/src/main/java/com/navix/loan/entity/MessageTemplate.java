package com.navix.loan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One template in the ADMIN Templates library (V97). Column meanings are in the migration header. */
@Entity
@Table(name = "message_template")
@Getter
@Setter
@NoArgsConstructor
public class MessageTemplate {

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String ACTIVE = "ACTIVE";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "category", nullable = false, length = 16)
    private String category;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "provider_id", length = 128)
    private String providerId;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    /** JSON array, one entry per placeholder. */
    @Column(name = "variables", nullable = false, columnDefinition = "text")
    private String variables = "[]";

    @Column(name = "rule", nullable = false, length = 16)
    private String rule = "LIVE";

    @Column(name = "confirm_text", length = 300)
    private String confirmText;

    @Column(name = "system_managed", nullable = false)
    private boolean systemManaged;

    @Column(name = "created_by_staff_id")
    private Long createdByStaffId;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void stamp() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
