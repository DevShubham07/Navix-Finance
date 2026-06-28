package com.navix.notification.entity;

import com.navix.common.notification.NotificationCategory;
import com.navix.common.notification.RecipientType;
import com.navix.notification.catalog.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One delivered notification for one recipient — the persisted in-app inbox row and the parent of
 * its per-channel {@link NotificationDelivery} rows. Plain {@code @Entity} (own identity id +
 * {@code created_at}); scoped on read by {@code (recipientType, recipientId)}.
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 16)
    private RecipientType recipientType;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 64)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private NotificationCategory category;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    /** Whether this notification shows in the in-app inbox (the bell). */
    @Column(name = "in_app", nullable = false)
    private boolean inApp = true;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "loan_id")
    private Long loanId;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "actor_id", length = 64)
    private String actorId;

    @Column(name = "actor_role", length = 64)
    private String actorRole;

    /** Optional idempotency key (unused in v1). */
    @Column(name = "dedupe_key", length = 120)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
