package com.navix.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One suppressed email address — SES reported a hard bounce or a complaint for it (or it was
 * suppressed manually), so the {@code EmailSender} must skip future sends to it. Populated by the
 * {@code SesEventSqsListener}. Distinct from the navix-iam fraud blocklist and from borrower
 * channel opt-outs. {@code reason}/{@code subType} carry the SES classification for audit.
 */
@Entity
@Table(name = "email_suppression")
@Getter
@Setter
@NoArgsConstructor
public class EmailSuppression {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "reason", nullable = false, length = 16)
    private String reason;

    @Column(name = "sub_type", length = 60)
    private String subType;

    @Column(name = "ses_message_id", length = 160)
    private String sesMessageId;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
