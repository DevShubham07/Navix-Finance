package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.sms.SmsGateway;
import com.navix.common.whatsapp.WhatsAppGateway;
import com.navix.loan.entity.ApplicationReference;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationReferenceRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReferenceOtpServiceTest {

    private static final long APP = 9L;

    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final ApplicationReferenceRepository references = mock(ApplicationReferenceRepository.class);
    private final LoanApplicationRepository applications = mock(LoanApplicationRepository.class);
    private final CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
    private final JourneyService journey = mock(JourneyService.class);
    private final SmsGateway sms = mock(SmsGateway.class);
    private final WhatsAppGateway whatsApp = mock(WhatsAppGateway.class);
    private final ReferenceOtpService service =
            new ReferenceOtpService(flags, references, applications, profiles, journey, sms, whatsApp);

    private ApplicationReference one;
    private ApplicationReference two;

    private static ApplicationReference ref(long id, int slot, String mobile) {
        ApplicationReference r = new ApplicationReference();
        r.setId(id);
        r.setSlot((short) slot);
        r.setFullName("Ravi Kumar");
        r.setMobile(mobile);
        r.setOtpRequired(true);
        return r;
    }

    @BeforeEach
    void setUp() {
        when(flags.isEnabled(ReferenceOtpService.FLAG, false)).thenReturn(true);
        LoanApplication app = new LoanApplication();
        app.setStatus(ApplicationStatus.SANCTIONED);
        when(applications.findById(APP)).thenReturn(Optional.of(app));
        CustomerProfile profile = new CustomerProfile();
        profile.setFullName("Asha Rao");
        when(profiles.findByApplicationId(APP)).thenReturn(Optional.of(profile));
        one = ref(1L, 1, "9876543210");
        two = ref(2L, 2, "9876543211");
        when(references.findByApplicationIdAndSlot(APP, (short) 1)).thenReturn(Optional.of(one));
        when(references.findByApplicationIdOrderBySlotAsc(APP)).thenReturn(List.of(one, two));
        lenient().when(references.claimOtpSend(any(), any(), any(), any(), any(), anyInt())).thenReturn(1);
        lenient().when(references.claimOtpAttempt(any(), any(), anyInt())).thenReturn(1);
        lenient().when(references.markOtpVerified(any(), any())).thenAnswer(i -> {
            one.setVerifiedAt(Instant.now());
            return 1;
        });
    }

    private String sentCode() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sms).send(eq("919876543210"), text.capture(), eq("REFERENCE_OTP"));
        Matcher m = Pattern.compile("^DhanBoost customer Asha Rao named you as a loan reference\\. If you agree, "
                + "share OTP (\\d{6}) with them\\. - DhanBoost$").matcher(text.getValue());
        assertThat(m.matches()).as(text.getValue()).isTrue();
        return m.group(1);
    }

    private void stubStoredHash() {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(references).claimOtpSend(eq(1L), hash.capture(), any(), any(), any(), eq(ReferenceOtpService.MAX_SENDS));
        one.setOtpHash(hash.getValue());
    }

    @Test
    void sendsTheRegisteredTextAndTheNoticeOnlyToANewNumber() {
        service.send(APP, 1);
        sentCode();
        verify(whatsApp).sendTemplate("919876543210", ReferenceOtpService.WHATSAPP_NOTICE, List.of("Ravi Kumar", "Asha Rao"));
        verify(whatsApp).sendOtp(eq("919876543210"), anyString());

        one.setOtpLastSentAt(Instant.now());
        org.mockito.Mockito.clearInvocations(whatsApp);
        service.send(APP, 1);
        verify(whatsApp, never()).sendTemplate(any(), any(), any());
    }

    @Test
    void oneChannelFailingStillCounts() {
        when(sms.send(any(), any(), any())).thenThrow(new RuntimeException("dlt"));
        service.send(APP, 1);
        verify(whatsApp).sendOtp(eq("919876543210"), anyString());
    }

    @Test
    void aFailedNoticeDoesNotStopTheWhatsAppCode() {
        when(whatsApp.sendTemplate(any(), any(), any())).thenThrow(new RuntimeException("template pending"));
        service.send(APP, 1);
        verify(whatsApp).sendOtp(eq("919876543210"), anyString());
    }

    @Test
    void refusesWhenBothChannelsFail() {
        when(sms.send(any(), any(), any())).thenThrow(new RuntimeException("dlt"));
        when(whatsApp.sendOtp(any(), any())).thenThrow(new RuntimeException("meta"));
        assertThatThrownBy(() -> service.send(APP, 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("could not send");
    }

    @Test
    void aRefusedClaimSendsNothing() {
        when(references.claimOtpSend(any(), any(), any(), any(), any(), anyInt())).thenReturn(0);
        assertThatThrownBy(() -> service.send(APP, 1)).hasMessageContaining("wait a minute");
        one.setOtpSendCount(ReferenceOtpService.MAX_SENDS);
        assertThatThrownBy(() -> service.send(APP, 1)).hasMessageContaining("Too many");
        verify(sms, never()).send(any(), any(), any());
    }

    @Test
    void theRightCodeConfirmsButTheStepWaitsForTheOtherReference() {
        service.send(APP, 1);
        String code = sentCode();
        stubStoredHash();

        service.verify(APP, 1, " " + code + " ");

        verify(references).markOtpVerified(eq(1L), any());
        verify(journey, never()).advance(APP, JourneyService.OfferStep.OFFER_SUMMARY);
    }

    @Test
    void theLastConfirmationMovesTheBorrowerOn() {
        two.setVerifiedAt(Instant.now());
        service.send(APP, 1);
        String code = sentCode();
        stubStoredHash();

        service.verify(APP, 1, code);

        verify(journey).advance(APP, JourneyService.OfferStep.OFFER_SUMMARY);
    }

    @Test
    void aWrongCodeIsRefused() {
        service.send(APP, 1);
        String code = sentCode();
        stubStoredHash();
        String wrong = code.equals("000000") ? "000001" : "000000";

        assertThatThrownBy(() -> service.verify(APP, 1, wrong)).hasMessageContaining("not correct");
        verify(references, never()).markOtpVerified(any(), any());
    }

    @Test
    void anExpiredOrLockedCodeIsNeverCompared() {
        one.setOtpHash("x");
        when(references.claimOtpAttempt(any(), any(), anyInt())).thenReturn(0);
        assertThatThrownBy(() -> service.verify(APP, 1, "123456")).hasMessageContaining("expired");
        verify(references, never()).markOtpVerified(any(), any());
    }

    @Test
    void flagOffRefusesEverything() {
        when(flags.isEnabled(ReferenceOtpService.FLAG, false)).thenReturn(false);
        assertThatThrownBy(() -> service.send(APP, 1)).hasMessageContaining("not switched on");
        verify(sms, never()).send(any(), any(), any());
    }

    @Test
    void pendingIsRequiredAndUnconfirmedAndANewNumberStartsOver() {
        assertThat(one.isOtpPending()).isTrue();
        one.setVerifiedAt(Instant.now());
        assertThat(one.isOtpPending()).isFalse();
        one.clearOtp();
        assertThat(one.isOtpPending()).isTrue();
    }
}
