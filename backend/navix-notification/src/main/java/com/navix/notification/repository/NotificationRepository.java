package com.navix.notification.repository;

import com.navix.common.notification.RecipientType;
import com.navix.notification.entity.Notification;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link Notification}, always scoped by the {@code (recipientType, recipientId)} owner. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** The owner's notifications, newest-first (page via {@code Pageable}). */
    List<Notification> findByRecipientTypeAndRecipientIdOrderByCreatedAtDesc(
            RecipientType recipientType, Long recipientId, Pageable pageable);

    /** Hot path: unread in-app count for the bell (served by the partial index). */
    long countByRecipientTypeAndRecipientIdAndInAppTrueAndReadAtIsNull(
            RecipientType recipientType, Long recipientId);

    /** Mark one notification read — scoped to its owner so a cross-recipient id changes nothing. */
    @Modifying
    @Query("update Notification n set n.readAt = :at where n.id = :id "
            + "and n.recipientType = :rt and n.recipientId = :rid and n.readAt is null")
    int markRead(@Param("id") Long id, @Param("rt") RecipientType rt,
                 @Param("rid") Long rid, @Param("at") Instant at);

    /** Mark all of the owner's unread notifications read. */
    @Modifying
    @Query("update Notification n set n.readAt = :at "
            + "where n.recipientType = :rt and n.recipientId = :rid and n.readAt is null")
    int markAllRead(@Param("rt") RecipientType rt, @Param("rid") Long rid, @Param("at") Instant at);
}
