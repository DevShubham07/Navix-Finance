package com.navix.notification.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.notification.RecipientType;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.notification.dto.NotificationView;
import com.navix.notification.entity.Notification;
import com.navix.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read/write surface for the current recipient's in-app inbox. The recipient is resolved from the
 * {@link CurrentActor} ({@code BORROWER} → customer inbox, else staff inbox) and <b>every</b> query
 * is scoped by {@code (recipientType, recipientId)}, so one recipient can never see or mutate another's.
 */
@Service
public class NotificationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<NotificationView> list(int page, int size) {
        Owner owner = currentOwner();
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return repository.findByRecipientTypeAndRecipientIdOrderByCreatedAtDesc(
                        owner.type(), owner.id(), PageRequest.of(Math.max(page, 0), safeSize))
                .stream().map(NotificationView::of).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        Owner owner = currentOwner();
        return repository.countByRecipientTypeAndRecipientIdAndInAppTrueAndReadAtIsNull(owner.type(), owner.id());
    }

    /** Mark one notification read (idempotent); a cross-recipient id yields 404. Returns the fresh unread count. */
    @Transactional
    public long markRead(Long id) {
        Owner owner = currentOwner();
        Notification n = repository.findById(id)
                .filter(x -> x.getRecipientType() == owner.type() && owner.id().equals(x.getRecipientId()))
                .orElseThrow(() -> new ResourceNotFoundException("Notification", String.valueOf(id)));
        if (n.getReadAt() == null) {
            repository.markRead(id, owner.type(), owner.id(), Instant.now());
        }
        return repository.countByRecipientTypeAndRecipientIdAndInAppTrueAndReadAtIsNull(owner.type(), owner.id());
    }

    /** Mark all of the caller's unread notifications read. Returns the fresh unread count (0). */
    @Transactional
    public long markAllRead() {
        Owner owner = currentOwner();
        repository.markAllRead(owner.type(), owner.id(), Instant.now());
        return repository.countByRecipientTypeAndRecipientIdAndInAppTrueAndReadAtIsNull(owner.type(), owner.id());
    }

    private Owner currentOwner() {
        CurrentActor actor = ActorContext.get();
        Long id = parseLongOrNull(actor.id());
        if (id == null) {
            throw new BusinessException("UNAUTHENTICATED", "No notification recipient bound to the request");
        }
        RecipientType type = "BORROWER".equals(actor.role()) ? RecipientType.BORROWER : RecipientType.STAFF;
        return new Owner(type, id);
    }

    private static Long parseLongOrNull(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Owner(RecipientType type, Long id) {
    }
}
