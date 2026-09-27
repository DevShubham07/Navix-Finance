package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.notification.event.VerificationStepLinkEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.lang.reflect.Field;
import java.time.Instant;
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
 * "Send the customer a link" — the staff nudge that reopens a failed/abandoned Phase-3 check and
 * sends the borrower back to it. Mirrors {@link BureauChallengeOutreachServiceTest}: this class sends
 * notifications and mutates one verification row, so the risk is not spend but sending the WRONG
 * borrower to the WRONG (or already-finished) screen.
 */
@ExtendWith(MockitoExtension.class)
class VerificationOutreachServiceTest {

    private static final Long APP = 42L;

    @Mock private ApplicationVerificationRepository verificationRepo;
    @Mock private LoanApplicationRepository applicationRepo;
    @Mock private ApplicationEventRepository eventRepo;
    @Mock private CustomerProfileRepository profileRepo;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private JourneyService journeyService;
    @Mock private BureauChallengeOutreachService bureauChallengeOutreach;

    private VerificationOutreachService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new VerificationOutreachService(verificationRepo, applicationRepo, eventRepo, profileRepo,
                eventPublisher, new ObjectMapper(), journeyService, bureauChallengeOutreach);
        Field field = VerificationOutreachService.class.getDeclaredField("frontendBaseUrl");
        field.setAccessible(true);
        field.set(service, "https://dhanboost.com");

        ActorContext.set(new CurrentActor("ce-1", "Credit Exec", "CREDIT_EXECUTIVE"));
        lenientDefaults();
    }

    private void lenientDefaults() {
        org.mockito.Mockito.lenient().when(eventRepo.findByApplicationIdAndActionOrderByAtDesc(any(), any()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private static LoanApplication sanctioned() {
        LoanApplication app = new LoanApplication();
        app.setId(APP);
        app.setCustomerId(501L);
        app.setStatus(ApplicationStatus.SANCTIONED);
        return app;
    }

    private static ApplicationVerification row(String type, String status) {
        ApplicationVerification v = new ApplicationVerification();
        v.setApplicationId(APP);
        v.setCheckType(type);
        v.setStatus(status);
        return v;
    }

    @Test
    void previewUrlEncodesTheOfferStepRoute() {
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(sanctioned()));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "SELFIE")).thenReturn(Optional.empty());

        var link = service.preview(APP, "SELFIE");

        assertThat(link.route()).isEqualTo("/loan/selfie");
        assertThat(link.url()).isEqualTo("https://dhanboost.com/login?next=%2Floan%2Fselfie");
        assertThat(link.willReopen()).isFalse();
    }

    @Test
    void esignWithAPassRowIsRefused() {
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(sanctioned()));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "ESIGN"))
                .thenReturn(Optional.of(row("ESIGN", "PASS")));

        assertThatThrownBy(() -> service.preview(APP, "ESIGN"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("STEP_ALREADY_DONE"));
    }

    @Test
    void wrongApplicationStatusIsRefused() {
        LoanApplication app = sanctioned();
        app.setStatus(ApplicationStatus.KYC_PENDING);
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.preview(APP, "SELFIE"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("STEP_LINK_NOT_ELIGIBLE"));
    }

    @Test
    void shareEmailReopensRewindsPublishesAndAudits() {
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(sanctioned()));
        ApplicationVerification existing = row("SELFIE", "FAIL");
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "SELFIE")).thenReturn(Optional.of(existing));

        var link = service.share(APP, "SELFIE", VerificationOutreachService.Channel.EMAIL);

        assertThat(link.willReopen()).isTrue();
        assertThat(existing.getStatus()).isEqualTo("PENDING");
        assertThat(existing.getReopenedAt()).isNotNull();
        assertThat(existing.getReopenedBy()).isEqualTo("ce-1");
        verify(verificationRepo).save(existing);
        verify(journeyService).rewind(APP, JourneyService.OfferStep.OFFER_SELFIE);

        ArgumentCaptor<ApplicationEvent> events = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventRepo, org.mockito.Mockito.times(2)).save(events.capture());
        assertThat(events.getAllValues()).extracting(ApplicationEvent::getAction)
                .containsExactly("STEP_REOPENED", "STEP_LINK_SENT");

        ArgumentCaptor<VerificationStepLinkEvent> published = ArgumentCaptor.forClass(VerificationStepLinkEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().applicationId()).isEqualTo(APP);
        assertThat(published.getValue().route()).isEqualTo("/loan/selfie");
        verify(bureauChallengeOutreach, never()).notifyApplication(any());
    }

    @Test
    void shareCopyReopensWithoutPublishing() {
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(sanctioned()));
        ApplicationVerification existing = row("ADDRESS", "REVIEW");
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "ADDRESS")).thenReturn(Optional.of(existing));

        var link = service.share(APP, "ADDRESS", VerificationOutreachService.Channel.COPY);

        assertThat(link.willReopen()).isTrue();
        assertThat(existing.getReopenedAt()).isNotNull();
        verify(journeyService).rewind(APP, JourneyService.OfferStep.OFFER_ADDRESS);
        verify(eventPublisher, never()).publishEvent(any());
        verify(bureauChallengeOutreach, never()).notifyApplication(any());
    }

    @Test
    void bureauDelegatesToTheBureauOutreachAndNeverReopens() {
        LoanApplication app = sanctioned();
        app.setStatus(ApplicationStatus.KYC_PENDING);
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "BUREAU"))
                .thenReturn(Optional.of(withDerived("{\"bureauChallenge\":true}")));
        when(bureauChallengeOutreach.notifyApplication(APP)).thenReturn(sent(1));

        var link = service.share(APP, "BUREAU", VerificationOutreachService.Channel.EMAIL);

        assertThat(link.willReopen()).isFalse();
        assertThat(link.route()).isEqualTo("/credit-question?appId=" + APP);
        verify(bureauChallengeOutreach).notifyApplication(APP);
        verify(eventPublisher, never()).publishEvent(any());
        verify(journeyService, never()).rewind(any(), any());
        // Never reopens, so only STEP_LINK_SENT is written — not STEP_REOPENED.
        ArgumentCaptor<ApplicationEvent> events = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventRepo).save(events.capture());
        assertThat(events.getValue().getAction()).isEqualTo("STEP_LINK_SENT");
    }

    @Test
    void bureauEmailSkippedByTheOnceOnlyStampIsAnErrorNotAnAuditedSend() {
        LoanApplication app = sanctioned();
        app.setStatus(ApplicationStatus.KYC_PENDING);
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(app));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "BUREAU"))
                .thenReturn(Optional.of(withDerived("{\"bureauChallenge\":true}")));
        when(bureauChallengeOutreach.notifyApplication(APP)).thenReturn(sent(0));

        assertThatThrownBy(() -> service.share(APP, "BUREAU", VerificationOutreachService.Channel.EMAIL))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already been emailed");
        verify(eventRepo, never()).save(any());
    }

    @Test
    void aSecondSendWhileTheReopenIsStillOpenDoesNotReopenAgain() {
        when(applicationRepo.findById(APP)).thenReturn(Optional.of(sanctioned()));
        ApplicationVerification reopened = row("SELFIE", "PENDING");
        reopened.setReopenedAt(Instant.parse("2026-09-01T00:00:00Z"));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "SELFIE")).thenReturn(Optional.of(reopened));

        var link = service.share(APP, "SELFIE", VerificationOutreachService.Channel.COPY);

        assertThat(link.willReopen()).isFalse();
        assertThat(reopened.getReopenedAt()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        verify(verificationRepo, never()).save(any());
        verify(journeyService, never()).rewind(any(), any());
        ArgumentCaptor<ApplicationEvent> events = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventRepo).save(events.capture());
        assertThat(events.getValue().getAction()).isEqualTo("STEP_LINK_SENT");
    }

    private static BureauChallengeOutreachService.OutreachSummary sent(int notified) {
        return new BureauChallengeOutreachService.OutreachSummary(1, notified, 1 - notified, false,
                List.of());
    }

    @Test
    void nonCreditRoleIsRejected() {
        ActorContext.set(new CurrentActor("tc-1", "Telecaller", "TELECALLER"));

        assertThatThrownBy(() -> service.preview(APP, "SELFIE")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.share(APP, "SELFIE", VerificationOutreachService.Channel.EMAIL))
                .isInstanceOf(BusinessException.class);
    }

    private static ApplicationVerification withDerived(String derivedJson) {
        ApplicationVerification v = row("BUREAU", "REVIEW");
        v.setDerived(derivedJson);
        return v;
    }
}
