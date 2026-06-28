package com.navix.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.notification.RecipientType;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.notification.entity.Notification;
import com.navix.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Every read/write is scoped to the {@link CurrentActor}; a cross-recipient id is invisible (404). */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository repository;

    private NotificationService service() {
        return new NotificationService(repository);
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private static Notification owned(long id, RecipientType type, long recipientId) {
        Notification n = new Notification();
        n.setId(id);
        n.setRecipientType(type);
        n.setRecipientId(recipientId);
        return n;
    }

    @Test
    void unreadCountScopesToBorrowerOwner() {
        ActorContext.set(new CurrentActor("5", "Asha", "BORROWER"));
        when(repository.countByRecipientTypeAndRecipientIdAndInAppTrueAndReadAtIsNull(RecipientType.BORROWER, 5L))
                .thenReturn(3L);

        assertThat(service().unreadCount()).isEqualTo(3L);
    }

    @Test
    void unreadCountScopesToStaffOwnerForNonBorrowerRole() {
        ActorContext.set(new CurrentActor("9", "Head", "CREDIT_HEAD"));
        when(repository.countByRecipientTypeAndRecipientIdAndInAppTrueAndReadAtIsNull(RecipientType.STAFF, 9L))
                .thenReturn(1L);

        assertThat(service().unreadCount()).isEqualTo(1L);
    }

    @Test
    void markReadOnOwnUnreadUpdatesAndReturnsFreshCount() {
        ActorContext.set(new CurrentActor("5", "Asha", "BORROWER"));
        when(repository.findById(100L)).thenReturn(Optional.of(owned(100L, RecipientType.BORROWER, 5L)));
        when(repository.countByRecipientTypeAndRecipientIdAndInAppTrueAndReadAtIsNull(RecipientType.BORROWER, 5L))
                .thenReturn(0L);

        long fresh = service().markRead(100L);

        assertThat(fresh).isZero();
        verify(repository).markRead(eq(100L), eq(RecipientType.BORROWER), eq(5L), any(Instant.class));
    }

    @Test
    void markReadRejectsAnotherRecipientsNotification() {
        // Actor is the borrower, but the row belongs to a staff inbox → 404, no mutation.
        ActorContext.set(new CurrentActor("5", "Asha", "BORROWER"));
        when(repository.findById(100L)).thenReturn(Optional.of(owned(100L, RecipientType.STAFF, 9L)));

        assertThatThrownBy(() -> service().markRead(100L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).markRead(any(), any(), any(), any());
    }

    @Test
    void markAllReadScopesToOwnerAndReturnsZero() {
        ActorContext.set(new CurrentActor("9", "Head", "CREDIT_HEAD"));
        when(repository.countByRecipientTypeAndRecipientIdAndInAppTrueAndReadAtIsNull(RecipientType.STAFF, 9L))
                .thenReturn(0L);

        assertThat(service().markAllRead()).isZero();
        verify(repository).markAllRead(eq(RecipientType.STAFF), eq(9L), any(Instant.class));
    }
}
