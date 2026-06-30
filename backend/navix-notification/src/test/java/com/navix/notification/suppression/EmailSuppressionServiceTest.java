package com.navix.notification.suppression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.notification.entity.EmailSuppression;
import com.navix.notification.repository.EmailSuppressionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Idempotent suppression + the send-time guard, with a mocked repository. */
class EmailSuppressionServiceTest {

    private final EmailSuppressionRepository repo = org.mockito.Mockito.mock(EmailSuppressionRepository.class);
    private final EmailSuppressionService service = new EmailSuppressionService(repo);

    @Test
    void savesNewSuppression() {
        when(repo.existsByEmailIgnoreCase("bad@x.test")).thenReturn(false);

        service.suppress("bad@x.test", "BOUNCE", "Permanent", "msg-1", "smtp; 550 no such user");

        ArgumentCaptor<EmailSuppression> captor = ArgumentCaptor.forClass(EmailSuppression.class);
        verify(repo).save(captor.capture());
        EmailSuppression saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("bad@x.test");
        assertThat(saved.getReason()).isEqualTo("BOUNCE");
        assertThat(saved.getSubType()).isEqualTo("Permanent");
        assertThat(saved.getSesMessageId()).isEqualTo("msg-1");
    }

    @Test
    void isIdempotentForAlreadySuppressed() {
        when(repo.existsByEmailIgnoreCase("bad@x.test")).thenReturn(true);

        service.suppress("bad@x.test", "COMPLAINT", null, "msg-2", null);

        verify(repo, never()).save(any());
    }

    @Test
    void ignoresBlankEmail() {
        service.suppress("  ", "BOUNCE", null, "msg-3", null);
        service.suppress(null, "BOUNCE", null, "msg-3", null);

        verify(repo, never()).existsByEmailIgnoreCase(anyString());
        verify(repo, never()).save(any());
    }

    @Test
    void truncatesOverlongDetail() {
        when(repo.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        String longDetail = "x".repeat(2000);

        service.suppress("bad@x.test", "BOUNCE", "Permanent", "msg-4", longDetail);

        ArgumentCaptor<EmailSuppression> captor = ArgumentCaptor.forClass(EmailSuppression.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getDetail()).hasSize(1000);
    }

    @Test
    void isSuppressedDelegatesToRepoAndIsBlankSafe() {
        when(repo.existsByEmailIgnoreCase("known@x.test")).thenReturn(true);

        assertThat(service.isSuppressed("known@x.test")).isTrue();
        assertThat(service.isSuppressed("unknown@x.test")).isFalse();
        assertThat(service.isSuppressed(null)).isFalse();
        assertThat(service.isSuppressed("  ")).isFalse();
    }
}
