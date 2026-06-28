package com.navix.notification.dto;

import com.navix.notification.entity.Notification;
import java.time.Instant;
import java.util.UUID;

/**
 * Borrower/staff-facing view of one in-app notification. Carries the routing ids
 * ({@code applicationId}/{@code loanId}/{@code caseId}) so the bell can deep-link on click.
 */
public record NotificationView(
        Long id,
        String type,
        String category,
        String title,
        String body,
        boolean read,
        Long applicationId,
        Long loanId,
        UUID caseId,
        Instant createdAt) {

    public static NotificationView of(Notification n) {
        return new NotificationView(
                n.getId(),
                n.getType().name(),
                n.getCategory().name(),
                n.getTitle(),
                n.getBody(),
                n.getReadAt() != null,
                n.getApplicationId(),
                n.getLoanId(),
                n.getCaseId(),
                n.getCreatedAt());
    }
}
