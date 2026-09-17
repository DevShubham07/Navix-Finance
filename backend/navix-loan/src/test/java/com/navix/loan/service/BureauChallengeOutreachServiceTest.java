package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.notification.event.BureauQuestionPendingEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The ADMIN outreach sweep that chases borrowers whose bureau report is parked behind an unanswered
 * KBA question. It sends notifications and never calls Fintrix, so the risk it carries is not spend
 * but credibility: who it mails, and who it must leave alone.
 */
@ExtendWith(MockitoExtension.class)
class BureauChallengeOutreachServiceTest {

    @Mock private ApplicationVerificationRepository verificationRepo;
    @Mock private LoanApplicationRepository applicationRepo;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private BureauChallengeOutreachService service;

    @BeforeEach
    void setUp() {
        service = new BureauChallengeOutreachService(verificationRepo, applicationRepo, eventPublisher,
                new ObjectMapper());
        ActorContext.set(new CurrentActor("admin-1", "Admin", "ADMIN"));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private static ApplicationVerification bureauRow(Long applicationId, String derivedJson) {
        ApplicationVerification row = new ApplicationVerification();
        row.setApplicationId(applicationId);
        row.setCheckType("BUREAU");
        row.setStatus("REVIEW");
        row.setDerived(derivedJson);
        return row;
    }

    private static LoanApplication kycPending(Long id, Long customerId) {
        LoanApplication app = new LoanApplication();
        app.setId(id);
        app.setCustomerId(customerId);
        app.setStatus(ApplicationStatus.KYC_PENDING);
        return app;
    }

    /**
     * CRIF closes a KBA question after the attempts it allows are spent ({@code S02}) — applications
     * 9741 and 9474 reached that state in twelve seconds each. Mailing those borrowers "please answer
     * your security question" sends them to a screen where every option is already dead: they cannot
     * succeed, they will try anyway, and the only thing the message buys is a support call. The sweep
     * has to read the same {@code bureauChallengeExhausted} flag the answer path writes.
     *
     * <p>Asserted alongside a still-open question on purpose. A sweep that contacted nobody would pass
     * a test that only looked at the exhausted row, and would be just as broken.
     */
    @Test
    void anExhaustedChallengeIsSkippedWhileAnOpenOneIsStillChased() {
        when(verificationRepo.findByCheckTypeAndStatus("BUREAU", "REVIEW")).thenReturn(List.of(
                bureauRow(9741L, "{\"bureauChallenge\":true,\"bureauChallengeExhausted\":true}"),
                bureauRow(9750L, "{\"bureauChallenge\":true}")));
        when(applicationRepo.findById(9750L)).thenReturn(Optional.of(kycPending(9750L, 501L)));

        var summary = service.notifyPending(50);

        assertThat(summary.eligible()).isEqualTo(1);
        assertThat(summary.notified()).isEqualTo(1);
        assertThat(summary.rows()).extracting(BureauChallengeOutreachService.OutreachRow::applicationId)
                .containsExactly(9750L);
        // The closed question is dropped before the application is even loaded — nothing about it
        // reaches the send path.
        verify(applicationRepo, never()).findById(9741L);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOf(BureauQuestionPendingEvent.class);
        assertThat(((BureauQuestionPendingEvent) published.getValue()).applicationId()).isEqualTo(9750L);
        // The stamp that makes the sweep idempotent is merged into the existing derived, not over it.
        verify(verificationRepo).save(any());
    }
}
