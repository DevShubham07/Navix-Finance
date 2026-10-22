package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.verification.MandatePort;
import com.navix.common.verification.MandatePort.BankCapability;
import com.navix.common.verification.MandatePort.MandateRequest;
import com.navix.common.verification.MandatePort.MandateResult;
import com.navix.common.verification.MandatePort.MandateSession;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Mandate;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.MandateRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MandateServiceTest {

    @Mock MandatePort port;
    @Mock MandateRepository mandates;
    @Mock LoanApplicationRepository applications;
    @Mock CustomerProfileRepository profiles;
    @Mock com.navix.common.featureflag.FeatureFlagService featureFlags;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock com.navix.loan.repository.MandateDebitRepository debits;
    @Mock com.navix.loan.repository.CustomerMandateCeilingOverrideRepository ceilingOverrides;

    MandateService service;
    LoanApplication app;
    CustomerProfile profile;

    @BeforeEach
    void setUp() {
        service = new MandateService(port, mandates, applications, profiles, new LoanMath(), featureFlags,
                eventPublisher, debits, ceilingOverrides);
        app = new LoanApplication();
        app.setId(1L);
        app.setCustomerId(4471L);
        // The journey only ever routes a borrower to this step with the flag on and the account confirmed;
        // init() now enforces both itself, because POST .../mandate/init is reachable without the journey.
        app.setDisbursalConfirmedAt(java.time.Instant.now());
        app.setDisbursalAccountNumber("123456789012");
        app.setDisbursalIfsc("HDFC0001234");
        app.setDisbursalBank("HDFC Bank");
        app.setSanctionedAmountPaise(5_000_00L);
        profile = new CustomerProfile();
        profile.setFullName("Asha Rao");
        profile.setMobile("9876543210");
        profile.setMonthlySalaryPaise(40_000_00L);
        org.mockito.Mockito.lenient().when(featureFlags.isEnabled("digio-mandate", false)).thenReturn(true);
        org.mockito.Mockito.lenient().when(applications.findById(1L)).thenReturn(Optional.of(app));
        org.mockito.Mockito.lenient().when(profiles.findByApplicationId(1L)).thenReturn(Optional.of(profile));
    }

    private void bankOk() {
        when(port.liveBanks()).thenReturn(List.of(new BankCapability("HDFC", true, List.of("debit"))));
    }

    private MandateRequest register() {
        ArgumentCaptor<MandateRequest> c = ArgumentCaptor.forClass(MandateRequest.class);
        verify(port).register(c.capture());
        return c.getValue();
    }

    @Test
    void ceilingIsTwiceEligibleLimit() {
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DID1", "partial", "https://x"));
        var r = service.init(1L);
        MandateRequest req = register();
        assertThat(req.maximumAmountPaise()).isEqualTo(20_000_00L);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        assertThat(req.firstCollectionDate()).isEqualTo(today);
        assertThat(req.finalCollectionDate()).isEqualTo(today.plusYears(5));
        assertThat(r.derived().get("url")).isEqualTo("https://x");
        assertThat(r.status()).isEqualTo("PENDING");
    }

    @Test
    void ceilingFloorsAtTwiceSanctionedWhenOverrideExceedsFormula() {
        app.setSanctionedAmountPaise(15_000_00L);
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DID1", "partial", "https://x"));
        service.init(1L);
        assertThat(register().maximumAmountPaise()).isEqualTo(30_000_00L);
    }

    @Test
    void sanitisesApostropheAndHyphenFromCustomerName() {
        assertThat(MandateService.sanitiseName("O'Brien-Smith")).isEqualTo("OBrien Smith");
    }

    @Test
    void rejectsAccountNumberShorterThanEightDigits() {
        app.setDisbursalAccountNumber("1234567");
        assertThatThrownBy(() -> service.init(1L)).isInstanceOf(BusinessException.class);
        verify(port, never()).register(any());
    }

    @Test
    void crnIsCustomerIdAndAttemptNumber() {
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DID1", "partial", "https://x"));
        service.init(1L);
        assertThat(register().customerRefNumber()).isEqualTo("C4471A1");

        Mandate prev = new Mandate();
        prev.setAttemptNo(1);
        when(mandates.findFirstByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(Optional.of(prev));
        when(port.register(any())).thenReturn(new MandateSession("DID2", "partial", "https://y"));
        service.init(1L);
        ArgumentCaptor<MandateRequest> c = ArgumentCaptor.forClass(MandateRequest.class);
        verify(port, times(2)).register(c.capture());
        assertThat(c.getAllValues().get(1).customerRefNumber()).isEqualTo("C4471A2");
    }

    @Test
    void recoversByCrnInsteadOfCreatingTwiceOnTimeout() {
        bankOk();
        when(port.register(any())).thenThrow(new RuntimeException("timeout"));
        when(port.findByRef("C4471A1")).thenReturn(Optional.of(
                new MandateResult("DIDX", "partial", null, null, null, null, null, null)));
        service.init(1L);
        verify(port, times(1)).register(any());
        ArgumentCaptor<Mandate> c = ArgumentCaptor.forClass(Mandate.class);
        verify(mandates).save(c.capture());
        assertThat(c.getValue().getDigioMandateId()).isEqualTo("DIDX");
    }

    @Test
    void anEmptyBankListIsAnOutageNotEveryBankUnsupported() {
        when(port.liveBanks()).thenReturn(List.of());
        assertThatThrownBy(() -> service.init(1L)).isInstanceOf(BusinessException.class);
        verify(port, never()).register(any());
        verify(mandates, never()).save(any());
    }

    @Test
    void parksAsBankUnsupportedWhenBankCannotDoApiMandate() {
        when(port.liveBanks()).thenReturn(List.of(new BankCapability("HDFC", false, List.of())));
        service.init(1L);
        verify(port, never()).register(any());
        ArgumentCaptor<Mandate> c = ArgumentCaptor.forClass(Mandate.class);
        verify(mandates).save(c.capture());
        assertThat(c.getValue().getStatus()).isEqualTo(Mandate.Status.BANK_UNSUPPORTED);
        // I3 — the borrower is promised a call back, so somebody has to be told.
        verify(eventPublisher).publishEvent(any(
                com.navix.common.notification.event.MandateBankUnsupportedEvent.class));
    }

    private Mandate pending() {
        Mandate m = new Mandate();
        m.setDigioMandateId("DID1");
        m.setStatus(Mandate.Status.PENDING_AUTHORIZATION);
        when(mandates.findFirstByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(Optional.of(m));
        return m;
    }

    @Test
    void statusMarksActiveWhenUmrnPresentDespitePartialState() {
        Mandate m = pending();
        when(port.fetch("DID1")).thenReturn(new MandateResult("DID1", "partial", null, "UMRN123", null, null, null, null));
        var r = service.status(1L);
        assertThat(m.getStatus()).isEqualTo(Mandate.Status.ACTIVE);
        assertThat(r.status()).isEqualTo("PASS");
    }

    @Test
    void statusStaysPendingWhenNoUmrn() {
        Mandate m = pending();
        when(port.fetch("DID1")).thenReturn(new MandateResult("DID1", "partial", null, null, null, null, null, null));
        var r = service.status(1L);
        assertThat(m.getStatus()).isEqualTo(Mandate.Status.PENDING_AUTHORIZATION);
        assertThat(r.status()).isEqualTo("PENDING");
    }

    @Test
    void recoveredMandateWithoutUmrnDoesNotStrandBorrower() {
        bankOk();
        when(port.register(any()))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(new MandateSession("DID2", "partial", "https://y"));
        when(port.findByRef("C4471A1")).thenReturn(Optional.of(
                new MandateResult("DIDX", "partial", "created", null, null, null, null, null)));
        var first = service.init(1L);
        assertThat(first.status()).isEqualTo("FAIL");
        ArgumentCaptor<Mandate> c = ArgumentCaptor.forClass(Mandate.class);
        verify(mandates).save(c.capture());
        Mandate saved = c.getValue();
        assertThat(saved.getDigioMandateId()).isEqualTo("DIDX");
        assertThat(saved.getAttemptNo()).isEqualTo(1);

        when(mandates.findFirstByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(Optional.of(saved));
        var second = service.init(1L);
        ArgumentCaptor<MandateRequest> r = ArgumentCaptor.forClass(MandateRequest.class);
        verify(port, times(2)).register(r.capture());
        assertThat(r.getAllValues().get(1).customerRefNumber()).isEqualTo("C4471A2");
        assertThat(second.derived().get("url")).isEqualTo("https://y");
    }

    @Test
    void recoveredMandateWithUmrnIsActive() {
        bankOk();
        when(port.register(any())).thenThrow(new RuntimeException("timeout"));
        when(port.findByRef("C4471A1")).thenReturn(Optional.of(
                new MandateResult("DIDX", "auth_success", "ack_received", "UMRN9", null, null, null, null)));
        assertThat(service.init(1L).status()).isEqualTo("PASS");
    }

    @Test
    void stateMappingIsExplicitAndCaseInsensitive() {
        assertThat(MandateService.mapNoUmrn("AUTH_FAILED", null)).isEqualTo(Mandate.Status.AUTHORIZATION_FAILED);
        assertThat(MandateService.mapNoUmrn("auth_success", "NACK_RECEIVED")).isEqualTo(Mandate.Status.AUTHORIZATION_FAILED);
        assertThat(MandateService.mapNoUmrn("Expired", null)).isEqualTo(Mandate.Status.EXPIRED);
        assertThat(MandateService.mapNoUmrn("auth_success", "awaiting_ack")).isEqualTo(Mandate.Status.PENDING_AUTHORIZATION);
        assertThat(MandateService.mapNoUmrn("something_new", "weird")).isEqualTo(Mandate.Status.PENDING_AUTHORIZATION);
        assertThat(MandateService.mapNoUmrn("complete", null)).isEqualTo(Mandate.Status.PENDING_AUTHORIZATION);
    }

    private Mandate active(String last4, long max) {
        Mandate m = new Mandate();
        m.setDigioMandateId("OLD");
        m.setUmrn("UMRN0");
        m.setStatus(Mandate.Status.ACTIVE);
        m.setAccountLast4(last4);
        m.setIfsc("HDFC0001234");
        m.setMaximumAmountPaise(max);
        m.setAttemptNo(1);
        org.mockito.Mockito.lenient().when(mandates.findFirstByCustomerIdAndStatusAndUmrnIsNotNullOrderByIdDesc(
                4471L, Mandate.Status.ACTIVE)).thenReturn(Optional.of(m));
        return m;
    }

    @Test
    void reusesMandateWhenAccountAndCeilingMatch() {
        active("9012", 20_000_00L);
        assertThat(service.init(1L).status()).isEqualTo("PASS");
        verify(port, never()).register(any());
    }

    @Test
    void doesNotReuseMandateBoundToADifferentAccount() {
        active("0000", 20_000_00L);
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DID9", "partial", "https://z"));
        assertThat(service.init(1L).status()).isEqualTo("PENDING");
        verify(port).register(any());
    }

    @Test
    void doesNotReuseMandateWhoseCeilingIsTooSmall() {
        active("9012", 20_000_00L - 1);
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DID9", "partial", "https://z"));
        assertThat(service.init(1L).status()).isEqualTo("PENDING");
        verify(port).register(any());
    }

    @Test
    void initAndStatusAgreeWhenReusableMandateHasNewerFailedAttempt() {
        active("9012", 20_000_00L);
        Mandate newer = new Mandate();
        newer.setAttemptNo(2);
        newer.setStatus(Mandate.Status.AUTHORIZATION_FAILED);
        org.mockito.Mockito.lenient().when(mandates.findFirstByCustomerIdOrderByAttemptNoDesc(4471L))
                .thenReturn(Optional.of(newer));
        assertThat(service.init(1L).status()).isEqualTo("PASS");
        assertThat(service.status(1L).status()).isEqualTo("PASS");
        verify(port, never()).register(any());
    }

    /** I2 — the lock is taken before anything can be filed, so two concurrent inits cannot interleave. */
    @Test
    void locksTheCustomerBeforeFilingAnything() {
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DID1", "partial", "https://x"));
        service.init(1L);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(mandates, port);
        order.verify(mandates).lockCustomer(4471L);
        order.verify(port).register(any());
    }

    @Test
    void mandateRequiredIsFlagAndUsableMandateTogether() {
        app.setId(1L);
        when(featureFlags.isEnabled("digio-mandate", false)).thenReturn(false);
        assertThat(service.mandateRequired(app)).isFalse();

        when(featureFlags.isEnabled("digio-mandate", false)).thenReturn(true);
        assertThat(service.mandateRequired(app)).isTrue();
        active("9012", 20_000_00L);
        assertThat(service.mandateRequired(app)).isFalse();
    }

    @Test
    void recoveryWithoutAMandateIdRethrowsInsteadOfWritingNull() {
        bankOk();
        RuntimeException failure = new RuntimeException("timeout");
        when(port.register(any())).thenThrow(failure);
        when(port.findByRef("C4471A1")).thenReturn(Optional.of(
                new MandateResult(null, "partial", null, null, null, null, null, null)));
        assertThatThrownBy(() -> service.init(1L)).isSameAs(failure);
        verify(mandates, never()).save(any());
    }

    @Test
    void zeroCeilingThrowsInsteadOfRegistering() {
        profile.setMonthlySalaryPaise(null);
        app.setSanctionedAmountPaise(null);
        assertThatThrownBy(() -> service.init(1L)).isInstanceOf(BusinessException.class);
        verify(port, never()).register(any());
    }

    /**
     * C1 — the borrower authorises, then cancels at their bank: Digio returns the UMRN AND
     * {@code state=revoked}. Driven through the real {@code status()} path, not by hand-setting a status.
     */
    @Test
    void revokedMandateThatKeepsItsUmrnIsNotActive() {
        Mandate m = pending();
        when(port.fetch("DID1")).thenReturn(
                new MandateResult("DID1", "revoked", "revoked", "UMRN123", null, null, null, null));

        var r = service.status(1L);

        assertThat(m.getStatus()).isEqualTo(Mandate.Status.AUTHORIZATION_FAILED);
        assertThat(m.getUmrn()).isEqualTo("UMRN123"); // kept for reconciliation, but it is not usable
        assertThat(r.status()).isEqualTo("FAIL");
    }

    @Test
    void cancelledMandateThatKeepsItsUmrnIsNotActive() {
        Mandate m = pending();
        when(port.fetch("DID1")).thenReturn(
                new MandateResult("DID1", "cancelled", null, "UMRN123", null, null, null, null));
        service.status(1L);
        assertThat(m.getStatus()).isEqualTo(Mandate.Status.AUTHORIZATION_FAILED);
    }

    /** A revoked mandate must not be reusable, so the next init registers a fresh one. */
    @Test
    void doesNotReuseRevokedMandateThatKeepsItsUmrn() {
        Mandate revoked = pending();
        when(port.fetch("DID1")).thenReturn(
                new MandateResult("DID1", "auth_success", "revoked", "UMRN123", null, null, null, null));
        service.status(1L);
        assertThat(revoked.getStatus()).isNotEqualTo(Mandate.Status.ACTIVE);

        // reusable() looks for an ACTIVE row; the revoked one is not one, so a fresh mandate is filed.
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DID9", "partial", "https://z"));
        service.init(1L);
        verify(port).register(any());
    }

    @Test
    void statusWithZeroCeilingReportsStateInsteadOfThrowing() {
        profile.setMonthlySalaryPaise(null);
        app.setSanctionedAmountPaise(null);
        Mandate failed = new Mandate();
        failed.setAttemptNo(1);
        failed.setDigioMandateId("D");
        failed.setStatus(Mandate.Status.AUTHORIZATION_FAILED);
        when(mandates.findFirstByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(Optional.of(failed));
        assertThat(service.status(1L).status()).isEqualTo("FAIL");
    }

    /** A newer revoked attempt (its status set by the real apply() path) must not hide the usable one. */
    @Test
    void reusesOlderActiveMandateHiddenBehindNewerRevokedOne() {
        active("9012", 20_000_00L);
        Mandate newer = new Mandate();
        newer.setAttemptNo(2);
        newer.setDigioMandateId("DID2");
        newer.setUmrn("UMRN1");
        // What apply() now writes for a umrn-bearing revoked mandate — not a hand-picked status.
        MandateService.apply(newer, new MandateResult("DID2", "revoked", "revoked", "UMRN1", null, null, null, null));
        assertThat(newer.getStatus()).isEqualTo(Mandate.Status.AUTHORIZATION_FAILED);
        lenient().when(mandates.findFirstByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(Optional.of(newer));

        assertThat(service.init(1L).status()).isEqualTo("PASS");
        assertThat(service.status(1L).status()).isEqualTo("PASS");
        verify(port, never()).register(any());
    }

    @Test
    void hasUsableMandateAppliesAccountAndCeilingNotJustCustomer() {
        app.setId(1L);
        active("9012", 20_000_00L);                       // bound to ...9012 (the app's account), ceiling ok
        assertThat(service.hasUsableMandate(app)).isTrue();

        app.setDisbursalAccountNumber("999988887777");    // borrower designates a different account
        assertThat(service.hasUsableMandate(app)).isFalse();

        app.setDisbursalAccountNumber("123456789012");
        active("9012", 1_00L);                            // ceiling too low
        assertThat(service.hasUsableMandate(app)).isFalse();
    }

    @Test
    void staffViewCarriesLastFourOnlyAndFlagsOnlyBankUnsupportedAsOverridable() {
        Mandate m = new Mandate();
        m.setStatus(Mandate.Status.BANK_UNSUPPORTED);
        m.setAccountLast4("9012");
        m.setBankName("HDFC Bank");
        m.setDigioMandateId("NONE-4471-1");
        m.setLoanApplicationId(1L);
        when(mandates.findFirstByLoanApplicationIdOrderByAttemptNoDesc(1L)).thenReturn(Optional.of(m));

        MandateService.MandateView v = service.staffView(1L).orElseThrow();

        assertThat(v.accountLast4()).isEqualTo("9012");
        assertThat(v.overridable()).isTrue();
        assertThat(v.toString()).doesNotContain("123456789012");
        assertThat(service.isBankUnsupported(1L)).isTrue();

        m.setStatus(Mandate.Status.AUTHORIZATION_FAILED);
        assertThat(service.staffView(1L).orElseThrow().overridable()).isFalse();
        assertThat(service.isBankUnsupported(1L)).isFalse();
    }

    private Mandate attempt(Long appId, Mandate.Status st) {
        Mandate m = new Mandate();
        m.setLoanApplicationId(appId);
        m.setStatus(st);
        return m;
    }

    @Test
    void overrideRefusedWhenBankUnsupportedBelongsToAnEarlierApplication() {
        // Application 1 has no attempt of its own; the customer's old advance (app 99) was BANK_UNSUPPORTED.
        when(mandates.findFirstByLoanApplicationIdOrderByAttemptNoDesc(1L)).thenReturn(Optional.empty());
        lenient().when(mandates.findFirstByCustomerIdOrderByAttemptNoDesc(4471L))
                .thenReturn(Optional.of(attempt(99L, Mandate.Status.BANK_UNSUPPORTED)));

        assertThat(service.isBankUnsupported(1L)).isFalse();
        assertThat(service.staffView(1L)).isEmpty();
    }

    @Test
    void overrideRefusedWhenNoMandateRowExists() {
        when(mandates.findFirstByLoanApplicationIdOrderByAttemptNoDesc(1L)).thenReturn(Optional.empty());
        assertThat(service.isBankUnsupported(1L)).isFalse();
    }

    @Test
    void overrideRefusedWhenLatestAttemptIsExpiredOrPending() {
        for (Mandate.Status st : new Mandate.Status[] {Mandate.Status.EXPIRED, Mandate.Status.PENDING_AUTHORIZATION,
                Mandate.Status.AUTHORIZATION_FAILED, Mandate.Status.ACTIVE}) {
            when(mandates.findFirstByLoanApplicationIdOrderByAttemptNoDesc(1L)).thenReturn(Optional.of(attempt(1L, st)));
            assertThat(service.isBankUnsupported(1L)).as(st.name()).isFalse();
        }
    }

    @Test
    void staffViewShowsThisApplicationsAttemptNotAnotherApplications() {
        Mandate mine = attempt(1L, Mandate.Status.BANK_UNSUPPORTED);
        mine.setAttemptNo(1);
        mine.setAccountLast4("9012");
        Mandate theirs = attempt(2L, Mandate.Status.AUTHORIZATION_FAILED);
        theirs.setAttemptNo(2);
        theirs.setRejectReason("other advance");
        lenient().when(mandates.findFirstByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(Optional.of(theirs));
        when(mandates.findFirstByLoanApplicationIdOrderByAttemptNoDesc(1L)).thenReturn(Optional.of(mine));

        MandateService.MandateView v = service.staffView(1L).orElseThrow();

        assertThat(v.status()).isEqualTo("BANK_UNSUPPORTED");
        assertThat(v.rejectReason()).isNull();
        assertThat(v.overridable()).isEqualTo(service.isBankUnsupported(1L)).isTrue();
    }

    @Test
    void staffViewShowsCarriedOverMandateForAReborrowWithNoRowOnThisApplication() {
        when(mandates.findFirstByLoanApplicationIdOrderByAttemptNoDesc(1L)).thenReturn(Optional.empty());
        app.setId(1L); // usableMandate resolves the profile by app id
        Mandate old = active("9012", 1_000_000_00L); // earlier advance, same account, ceiling covers this one

        MandateService.MandateView v = service.staffView(1L).orElseThrow();

        assertThat(v.status()).isEqualTo("ACTIVE");
        assertThat(v.umrn()).isEqualTo(old.getUmrn());
        assertThat(v.carriedOver()).isTrue();
        assertThat(v.overridable()).isFalse();
        assertThat(service.isBankUnsupported(1L)).isFalse();
    }

    /**
     * Pins the reference against DIGIO'S OWN pattern, not ours. The first live registration attempt was
     * rejected HTTP 400 REQUEST_VALIDATION_FAILED because the reference was "7682036-1" and Digio allows
     * alphanumerics only. A test that asserted our old wider pattern would have passed throughout.
     */
    @Test
    void customerRefNumberIsAlphanumericAsDigioRequires() {
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DID1", "partial", "https://x"));
        service.init(1L);
        String crn = register().customerRefNumber();
        assertThat(crn).matches("^[0-9A-Za-z]{1,20}$").doesNotContain("-").contains("4471");
    }

    // ---- the duplicate-filing incident, 2026-10-20 (docs/digio/13 section 13) ------------------------

    private Mandate attempt(int no, Mandate.Status status, String umrn) {
        Mandate m = new Mandate();
        m.setId((long) no);
        m.setCustomerId(4471L);
        m.setLoanApplicationId(1L);
        m.setAttemptNo(no);
        m.setCustomerRefNumber("C4471A" + no);
        m.setDigioMandateId("ENA" + no);
        m.setStatus(status);
        m.setUmrn(umrn);
        m.setAccountLast4("9012");
        m.setIfsc("HDFC0001234");
        m.setMaximumAmountPaise(99_999_00L);
        m.setRegisteredAt(java.time.Instant.now());
        return m;
    }

    /**
     * THE incident. Digio's auth_failed is not terminal: the borrower retried on the same Digio page and
     * succeeded seven minutes after we had written the attempt off. init must re-read it and adopt the
     * UMRN rather than file a second NPCI mandate — that second filing is what made the bank start
     * refusing every verification mode as a duplicate.
     */
    @Test
    void adoptsAnAttemptThatSucceededAfterWeRecordedItAsFailed() {
        Mandate failed = attempt(1, Mandate.Status.AUTHORIZATION_FAILED, null);
        when(mandates.findByLoanApplicationIdOrderByAttemptNoDesc(1L)).thenReturn(List.of(failed));
        when(port.fetch("ENA1")).thenReturn(new MandateResult("ENA1", "auth_success", "ack_received",
                "KKBK7022010261001406", "debit", null, null, java.time.Instant.now()));

        var r = service.init(1L);

        assertThat(r.status()).isEqualTo("PASS");
        assertThat(failed.getStatus()).isEqualTo(Mandate.Status.ACTIVE);
        assertThat(failed.getUmrn()).isEqualTo("KKBK7022010261001406");
        verify(port, never()).register(any()); // the whole point: no second mandate at NPCI
    }

    /** A borrower who closed the tab or reloaded goes back to the SAME mandate, not a new one. */
    @Test
    void reusesALivePendingAttemptsAuthorisationUrlInsteadOfFilingAgain() {
        Mandate pending = attempt(1, Mandate.Status.PENDING_AUTHORIZATION, null);
        pending.setAuthenticationUrl("https://app.digio.in/eDIGIO/EXISTING");
        when(mandates.findByLoanApplicationIdOrderByAttemptNoDesc(1L)).thenReturn(List.of(pending));
        when(port.fetch("ENA1")).thenReturn(new MandateResult("ENA1", "partial", "partial",
                null, null, null, null, null));

        var r = service.init(1L);

        assertThat(r.derived().get("url")).isEqualTo("https://app.digio.in/eDIGIO/EXISTING");
        verify(port, never()).register(any());
    }

    /** An expired url cannot be reused — then, and only then, a fresh mandate is filed. */
    @Test
    void filesAFreshMandateOnceTheStoredUrlHasExpired() {
        Mandate stale = attempt(1, Mandate.Status.PENDING_AUTHORIZATION, null);
        stale.setAuthenticationUrl("https://app.digio.in/eDIGIO/OLD");
        stale.setRegisteredAt(java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS));
        when(mandates.findByLoanApplicationIdOrderByAttemptNoDesc(1L)).thenReturn(List.of(stale));
        when(mandates.findFirstByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(Optional.of(stale));
        when(port.fetch("ENA1")).thenReturn(new MandateResult("ENA1", "partial", "partial",
                null, null, null, null, null));
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DIDNEW", "partial", "https://x"));

        service.init(1L);

        verify(port).register(any());
        assertThat(register().customerRefNumber()).isEqualTo("C4471A2");
    }

    /** A provider read that fails must not block a borrower who has no usable mandate at all. */
    @Test
    void aFailedReReadFallsThroughToFilingRatherThanThrowing() {
        Mandate failed = attempt(1, Mandate.Status.AUTHORIZATION_FAILED, null);
        when(mandates.findByLoanApplicationIdOrderByAttemptNoDesc(1L)).thenReturn(List.of(failed));
        when(port.fetch("ENA1")).thenThrow(new RuntimeException("provider down"));
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DIDNEW", "partial", "https://x"));

        assertThat(service.init(1L).status()).isEqualTo("PENDING");
        verify(port).register(any());
    }

    /**
     * The staff tab read the NEWEST attempt, so a customer who already held a live mandate was shown
     * "Awaiting borrower authorisation" because attempt 8 was pending on top of an ACTIVE attempt 1.
     * The effective authority is what governs the application, not the most recent click.
     */
    @Test
    void staffViewShowsTheMandateInForceNotTheNewestAttempt() {
        Mandate active = attempt(1, Mandate.Status.ACTIVE, "KKBK7022010261001406");
        Mandate newer = attempt(8, Mandate.Status.PENDING_AUTHORIZATION, null);
        when(mandates.findFirstByCustomerIdAndStatusAndUmrnIsNotNullOrderByIdDesc(4471L, Mandate.Status.ACTIVE))
                .thenReturn(Optional.of(active));
        lenient().when(mandates.findFirstByLoanApplicationIdOrderByAttemptNoDesc(1L)).thenReturn(Optional.of(newer));

        var v = service.staffView(1L).orElseThrow();

        assertThat(v.status()).isEqualTo("ACTIVE");
        assertThat(v.umrn()).isEqualTo("KKBK7022010261001406");
        assertThat(v.attemptNo()).isEqualTo(1);
    }

    /** History is per CUSTOMER, and marks which attempt is actually in force. */
    @Test
    void historyListsEveryAttemptAndFlagsTheOneInForce() {
        Mandate active = attempt(1, Mandate.Status.ACTIVE, "KKBK7022010261001406");
        Mandate pending = attempt(2, Mandate.Status.PENDING_AUTHORIZATION, null);
        when(mandates.findFirstByCustomerIdAndStatusAndUmrnIsNotNullOrderByIdDesc(4471L, Mandate.Status.ACTIVE))
                .thenReturn(Optional.of(active));
        when(mandates.findByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(List.of(pending, active));

        var rows = service.history(1L);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).attemptNo()).isEqualTo(2);
        assertThat(rows.get(0).reusable()).isFalse();
        assertThat(rows.get(1).reusable()).isTrue();
    }

    /** refresh() re-reads what is unsettled and adopts a completion; it files nothing. */
    @Test
    void refreshAdoptsAnAttemptThatCompletedSinceWeGaveUp() {
        Mandate failed = attempt(1, Mandate.Status.AUTHORIZATION_FAILED, null);
        when(mandates.findByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(List.of(failed));
        when(port.fetch("ENA1")).thenReturn(new MandateResult("ENA1", "auth_success", "ack_received",
                "KKBK7022010261001406", "debit", null, null, java.time.Instant.now()));

        service.refresh(1L);

        assertThat(failed.getStatus()).isEqualTo(Mandate.Status.ACTIVE);
        assertThat(failed.getUmrn()).isEqualTo("KKBK7022010261001406");
        verify(port, never()).register(any());
    }

    /** The stale-duplicate case this was built for: withdraw our own un-authorised filing. */
    @Test
    void cancelWithdrawsAStalePendingAttempt() {
        Mandate pending = attempt(2, Mandate.Status.PENDING_AUTHORIZATION, null);
        when(mandates.findById(2L)).thenReturn(Optional.of(pending));
        when(port.cancelMandate("ENA2", null, "HDFC0001234", "C002")).thenReturn(true);
        when(port.fetch("ENA2")).thenReturn(new MandateResult("ENA2", "cancelled", "revoked",
                null, null, null, null, null));
        when(mandates.findByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(List.of(pending));

        service.cancel(1L, 2L);

        assertThat(pending.getStatus()).isEqualTo(Mandate.Status.AUTHORIZATION_FAILED);
        verify(port).cancelMandate("ENA2", null, "HDFC0001234", "C002");
    }

    /**
     * The guard that matters: the live authority collections depends on must never be cancellable from
     * a history row. No ACTIVE mandate is, in fact — one not in force here may be in force elsewhere.
     */
    @Test
    void cancelRefusesAnActiveMandate() {
        Mandate active = attempt(1, Mandate.Status.ACTIVE, "KKBK7022010261001406");
        when(mandates.findById(1L)).thenReturn(Optional.of(active));
        assertThatThrownBy(() -> service.cancel(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_ACTIVE_NOT_CANCELLABLE");
        verify(port, never()).cancelMandate(any(), any(), any(), any());
    }

    /** Another customer's mandate is not findable through this application. */
    @Test
    void cancelRefusesAMandateBelongingToSomeoneElse() {
        Mandate theirs = attempt(1, Mandate.Status.PENDING_AUTHORIZATION, null);
        theirs.setCustomerId(9999L);
        when(mandates.findById(1L)).thenReturn(Optional.of(theirs));
        assertThatThrownBy(() -> service.cancel(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_NOT_FOUND");
        verify(port, never()).cancelMandate(any(), any(), any(), any());
    }

    /** A provider refusal is reported, not swallowed — which states permit cancel is still unknown. */
    @Test
    void cancelSurfacesAProviderRefusal() {
        Mandate pending = attempt(2, Mandate.Status.PENDING_AUTHORIZATION, null);
        when(mandates.findById(2L)).thenReturn(Optional.of(pending));
        when(port.cancelMandate(any(), any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.cancel(1L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_CANCEL_REFUSED");
        assertThat(pending.getStatus()).isEqualTo(Mandate.Status.PENDING_AUTHORIZATION); // unchanged
    }

    // ---- cancelActive(): revoking the live authority, deliberately ---------------------------------------

    private Mandate activeForCancel() {
        Mandate active = attempt(1, Mandate.Status.ACTIVE, "KKBK7022010261001406");
        active.setBankName("HDFC Bank");
        lenient().when(mandates.findById(1L)).thenReturn(Optional.of(active));
        lenient().when(mandates.findByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(List.of(active));
        app.setLoanId(77L);
        lenient().when(applications.findByCustomerId(4471L)).thenReturn(List.of(app));
        return active;
    }

    @Test
    void cancelActiveRevokesTheLiveMandateRecordsWhoAndNotifies() {
        Mandate active = activeForCancel();
        when(port.cancelMandate("ENA1", "KKBK7022010261001406", "HDFC0001234", "C001")).thenReturn(true);
        when(port.fetch("ENA1")).thenReturn(new MandateResult("ENA1", "cancelled", null,
                "KKBK7022010261001406", null, null, null, null));

        var row = service.cancelActive(1L, 1L, MandateService.CancelReason.CUSTOMER_REQUEST, "Asked on call");

        assertThat(active.getStatus()).isEqualTo(Mandate.Status.AUTHORIZATION_FAILED);
        assertThat(active.getCancelledAt()).isNotNull();
        assertThat(active.getCancelReason()).isEqualTo("C001: Asked on call");
        assertThat(row.cancelledAt()).isNotNull();
        // The loan's debit lock is taken so a presentation cannot race the cancel.
        verify(debits).lockLoan(DebitService.LOCK_NAMESPACE | 77L);
        var c = ArgumentCaptor.forClass(com.navix.common.notification.event.MandateCancelledEvent.class);
        verify(eventPublisher).publishEvent(c.capture());
        assertThat(c.getValue().customerRequested()).isTrue();
        assertThat(c.getValue().accountLast4()).isEqualTo("9012");
    }

    /** Accepted by the provider but still read back as active: dead to us anyway, so no more debits. */
    @Test
    void cancelActiveTreatsAnAcceptedCancelAsFinalEvenIfTheBankLags() {
        Mandate active = activeForCancel();
        when(port.cancelMandate(any(), any(), any(), eq("C002"))).thenReturn(true);
        when(port.fetch("ENA1")).thenReturn(new MandateResult("ENA1", "register_success", null,
                "KKBK7022010261001406", null, null, null, null));

        service.cancelActive(1L, 1L, MandateService.CancelReason.CORPORATE_REQUEST, "Replacing with higher ceiling");

        assertThat(active.getStatus()).isNotEqualTo(Mandate.Status.ACTIVE);
        assertThat(active.getDigioState()).isEqualTo("cancel_requested");
        assertThat(service.hasUsableMandate(app)).isFalse();
    }

    @Test
    void cancelActiveRefusesWhileADebitIsInFlight() {
        activeForCancel();
        when(debits.existsByMandateIdAndStatusIn(eq(1L), any())).thenReturn(true);
        assertThatThrownBy(() -> service.cancelActive(1L, 1L, MandateService.CancelReason.CUSTOMER_REQUEST, "x"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_DEBIT_IN_FLIGHT");
        verify(port, never()).cancelMandate(any(), any(), any(), any());
    }

    @Test
    void cancelActiveNeedsANote() {
        activeForCancel();
        assertThatThrownBy(() -> service.cancelActive(1L, 1L, MandateService.CancelReason.CUSTOMER_REQUEST, "  "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "CANCEL_NOTES_REQUIRED");
        verify(port, never()).cancelMandate(any(), any(), any(), any());
    }

    @Test
    void cancelActiveRefusesAnAttemptThatIsNotActive() {
        Mandate pending = attempt(2, Mandate.Status.PENDING_AUTHORIZATION, null);
        when(mandates.findById(2L)).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> service.cancelActive(1L, 2L, MandateService.CancelReason.CUSTOMER_REQUEST, "x"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_NOT_ACTIVE");
    }

    @Test
    void cancelActiveRefusesAnotherCustomersMandate() {
        Mandate theirs = attempt(1, Mandate.Status.ACTIVE, "UMRN");
        theirs.setCustomerId(9999L);
        when(mandates.findById(1L)).thenReturn(Optional.of(theirs));
        assertThatThrownBy(() -> service.cancelActive(1L, 1L, MandateService.CancelReason.CUSTOMER_REQUEST, "x"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_NOT_FOUND");
    }

    /** A cancelled row is never re-read and re-adopted, even if Digio still says it is live. */
    @Test
    void refreshNeverReadoptsACancelledMandate() {
        Mandate cancelled = attempt(1, Mandate.Status.AUTHORIZATION_FAILED, "KKBK7022010261001406");
        cancelled.setCancelledAt(java.time.Instant.now());
        when(mandates.findByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(List.of(cancelled));
        service.refresh(1L);
        verify(port, never()).fetch(any());
        assertThat(cancelled.getStatus()).isEqualTo(Mandate.Status.AUTHORIZATION_FAILED);
    }

    /**
     * The bug behind "I can't raise my mandate": an ACTIVE mandate whose ceiling is below this advance's
     * was reported PASS, so the borrower saw "set up" and never got the button to register a bigger one.
     */
    @Test
    void statusDoesNotReportATooSmallActiveMandateAsDone() {
        Mandate small = attempt(1, Mandate.Status.ACTIVE, "UMRN-SMALL");
        small.setMaximumAmountPaise(1_000_00L); // below the 20,000 ceiling this application needs
        when(mandates.findFirstByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(Optional.of(small));

        var r = service.status(1L);

        assertThat(r.status()).isEqualTo("PENDING");
        assertThat(r.derived().get("status")).isEqualTo("NOT_STARTED");
    }

    /** After staff cancel a mandate, the borrower's screen offers a fresh start, not "setup failed". */
    @Test
    void statusAfterACancelledMandateReadsAsNotStarted() {
        Mandate cancelled = attempt(1, Mandate.Status.AUTHORIZATION_FAILED, "UMRN-OLD");
        cancelled.setCancelledAt(java.time.Instant.now());
        when(mandates.findFirstByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(Optional.of(cancelled));

        var r = service.status(1L);

        assertThat(r.status()).isEqualTo("PENDING");
        assertThat(r.derived().get("status")).isEqualTo("NOT_STARTED");
        verify(port, never()).fetch(any());
    }

    // ---- mandate-ceiling override (V89) ------------------------------------------------------------------

    private void ceilingOverride(long paise) {
        var o = new com.navix.loan.entity.CustomerMandateCeilingOverride();
        o.setCustomerId(4471L);
        o.setCeilingPaise(paise);
        when(ceilingOverrides.findById(4471L)).thenReturn(Optional.of(o));
    }

    /** An ADMIN override raises what a NEW mandate is filed for (formula here is 2 x 10,000 = 20,000). */
    @Test
    void initFilesAtTheOverrideCeilingWhenItIsHigher() {
        ceilingOverride(75_000_00L);
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DID9", "partial", "https://z"));

        service.init(1L);

        var req = ArgumentCaptor.forClass(MandateRequest.class);
        verify(port).register(req.capture());
        assertThat(req.getValue().maximumAmountPaise()).isEqualTo(75_000_00L);
    }

    /** It only ever raises: a lower override would file a mandate that does not cover the application. */
    @Test
    void anOverrideBelowTheFormulaIsIgnored() {
        ceilingOverride(5_000_00L);
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DID9", "partial", "https://z"));

        service.init(1L);

        var req = ArgumentCaptor.forClass(MandateRequest.class);
        verify(port).register(req.capture());
        assertThat(req.getValue().maximumAmountPaise()).isEqualTo(20_000_00L);
    }

    /** Setting an override must not make a working mandate "too small": coverage is judged on the formula. */
    @Test
    void anOverrideDoesNotInvalidateAnExistingMandate() {
        Mandate active = attempt(1, Mandate.Status.ACTIVE, "UMRN1");
        active.setMaximumAmountPaise(20_000_00L);
        when(mandates.findFirstByCustomerIdAndStatusAndUmrnIsNotNullOrderByIdDesc(4471L, Mandate.Status.ACTIVE))
                .thenReturn(Optional.of(active));
        // Stubbed but deliberately never read: the coverage check must not consult the override at all.
        var o = new com.navix.loan.entity.CustomerMandateCeilingOverride();
        o.setCustomerId(4471L);
        o.setCeilingPaise(75_000_00L);
        lenient().when(ceilingOverrides.findById(4471L)).thenReturn(Optional.of(o));

        assertThat(service.hasUsableMandate(app)).isTrue();
    }

    @Test
    void setCeilingOverrideRejectsOutOfRangeAmounts() {
        assertThatThrownBy(() -> service.setCeilingOverride(1L, 500_00L, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_CEILING_OUT_OF_RANGE");
        assertThatThrownBy(() -> service.setCeilingOverride(1L, 20_00_000_00L, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_CEILING_OUT_OF_RANGE");
    }

    @Test
    void ceilingViewShowsFormulaOverrideAndRegistration() {
        ceilingOverride(75_000_00L);
        var v = service.ceilingView(1L);
        assertThat(v.formulaPaise()).isEqualTo(20_000_00L);
        assertThat(v.overridePaise()).isEqualTo(75_000_00L);
        assertThat(v.registrationPaise()).isEqualTo(75_000_00L);
    }

    // ---- invite(): the only route for someone whose loan is already live -----------------------------

    /** The link is ours, never Digio's expiring authentication_url, and nothing is filed by inviting. */
    @Test
    void invitePublishesALinkToOurOwnPageAndFilesNothing() {
        String link = service.invite(1L);
        assertThat(link).isEqualTo("http://localhost:3000/loan/mandate");
        var c = ArgumentCaptor.forClass(com.navix.common.notification.event.MandateInviteEvent.class);
        verify(eventPublisher).publishEvent(c.capture());
        assertThat(c.getValue().link()).isEqualTo(link);
        assertThat(c.getValue().customerId()).isEqualTo(4471L);
        assertThat(c.getValue().name()).isEqualTo("Asha Rao");
        verify(port, never()).register(any());
    }

    /**
     * "Everyone without a mandate" cannot include someone whose bank details were never proven: init
     * would refuse them on arrival, and a link that leads to an error is worse than refusing here.
     */
    @Test
    void inviteRefusesWhenThereIsNoVerifiedAccountToMandate() {
        app.setDisbursalAccountNumber(null);
        assertThatThrownBy(() -> service.invite(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_NO_ACCOUNT");
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** No point nagging someone who already has one. */
    @Test
    void inviteRefusesWhenTheCustomerAlreadyHoldsAUsableMandate() {
        Mandate active = new Mandate();
        active.setStatus(Mandate.Status.ACTIVE);
        active.setUmrn("UMRN1");
        active.setAccountLast4("9012");
        active.setIfsc("HDFC0001234");
        active.setMaximumAmountPaise(99_999_00L);
        when(mandates.findFirstByCustomerIdAndStatusAndUmrnIsNotNullOrderByIdDesc(4471L, Mandate.Status.ACTIVE))
                .thenReturn(Optional.of(active));
        assertThatThrownBy(() -> service.invite(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_ALREADY_ACTIVE");
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---- the gates on init(): it is a borrower-reachable endpoint, not only a journey step ------------

    /**
     * With digio-mandate off NOTHING may file a mandate. The flag is the kill switch for a feature that
     * files real NPCI mandates and bills per create; the journey callers were gated and this endpoint
     * was not, so an authenticated borrower could drive it with the feature switched off.
     */
    @Test
    void refusesToFileAMandateWhileTheKillSwitchIsOff() {
        when(featureFlags.isEnabled("digio-mandate", false)).thenReturn(false);
        assertThatThrownBy(() -> service.init(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_NOT_ENABLED");
        verify(port, never()).register(any());
    }

    /** A mandate authorises debits from ONE account, so it may only be filed against the proven one. */
    @Test
    void refusesWhenTheAccountWasNeverConfirmedOrVerified() {
        app.setDisbursalConfirmedAt(null);
        app.setDisbursalAccountVerified(Boolean.FALSE);
        assertThatThrownBy(() -> service.init(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_ACCOUNT_UNCONFIRMED");
        verify(port, never()).register(any());
    }

    /**
     * The PRE_APPROVED fast-track has no confirm step, so a penny-dropped account carried from the last
     * advance is what stands in for it — otherwise that borrower is gated on a mandate they cannot file.
     */
    @Test
    void acceptsACarriedVerifiedAccountWithNoConfirmTimestamp() {
        app.setDisbursalConfirmedAt(null);
        app.setDisbursalAccountVerified(Boolean.TRUE);
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DID1", "partial", "https://x"));
        assertThat(service.init(1L).status()).isEqualTo("PENDING");
        verify(port).register(any());
    }

    /**
     * The cap is a read-then-write, so it must be read while the customer lock is HELD — otherwise two
     * concurrent inits both see MAX - 1 and both file, taking the total past the cap.
     */
    @Test
    void readsTheAttemptCountOnlyAfterTakingTheCustomerLock() {
        bankOk();
        when(port.register(any())).thenReturn(new MandateSession("DID1", "partial", "https://x"));
        service.init(1L);
        var order = org.mockito.Mockito.inOrder(mandates);
        order.verify(mandates).lockCustomer(4471L);
        order.verify(mandates).countByLoanApplicationId(1L);
    }

    /** Retrying is expected; looping is not. Every attempt is a billable provider create. */
    @Test
    void refusesOnceTheAttemptCapForThisApplicationIsReached() {
        when(mandates.countByLoanApplicationId(1L))
                .thenReturn((long) MandateService.MAX_ATTEMPTS_PER_APPLICATION);
        assertThatThrownBy(() -> service.init(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_ATTEMPTS_EXHAUSTED");
        verify(port, never()).register(any());
    }
}
