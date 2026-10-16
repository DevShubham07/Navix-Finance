package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    MandateService service;
    LoanApplication app;
    CustomerProfile profile;

    @BeforeEach
    void setUp() {
        service = new MandateService(port, mandates, applications, profiles, new LoanMath());
        app = new LoanApplication();
        app.setCustomerId(4471L);
        app.setDisbursalAccountNumber("123456789012");
        app.setDisbursalIfsc("HDFC0001234");
        app.setDisbursalBank("HDFC Bank");
        app.setSanctionedAmountPaise(5_000_00L);
        profile = new CustomerProfile();
        profile.setFullName("Asha Rao");
        profile.setMobile("9876543210");
        profile.setMonthlySalaryPaise(40_000_00L);
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
        assertThat(register().customerRefNumber()).isEqualTo("4471-1");

        Mandate prev = new Mandate();
        prev.setAttemptNo(1);
        when(mandates.findFirstByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(Optional.of(prev));
        when(port.register(any())).thenReturn(new MandateSession("DID2", "partial", "https://y"));
        service.init(1L);
        ArgumentCaptor<MandateRequest> c = ArgumentCaptor.forClass(MandateRequest.class);
        verify(port, times(2)).register(c.capture());
        assertThat(c.getAllValues().get(1).customerRefNumber()).isEqualTo("4471-2");
    }

    @Test
    void recoversByCrnInsteadOfCreatingTwiceOnTimeout() {
        bankOk();
        when(port.register(any())).thenThrow(new RuntimeException("timeout"));
        when(port.findByRef("4471-1")).thenReturn(Optional.of(
                new MandateResult("DIDX", "partial", null, null, null, null, null, null)));
        service.init(1L);
        verify(port, times(1)).register(any());
        ArgumentCaptor<Mandate> c = ArgumentCaptor.forClass(Mandate.class);
        verify(mandates).save(c.capture());
        assertThat(c.getValue().getDigioMandateId()).isEqualTo("DIDX");
    }

    @Test
    void parksAsBankUnsupportedWhenBankCannotDoApiMandate() {
        when(port.liveBanks()).thenReturn(List.of(new BankCapability("HDFC", false, List.of())));
        service.init(1L);
        verify(port, never()).register(any());
        ArgumentCaptor<Mandate> c = ArgumentCaptor.forClass(Mandate.class);
        verify(mandates).save(c.capture());
        assertThat(c.getValue().getStatus()).isEqualTo(Mandate.Status.BANK_UNSUPPORTED);
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
        when(port.findByRef("4471-1")).thenReturn(Optional.of(
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
        assertThat(r.getAllValues().get(1).customerRefNumber()).isEqualTo("4471-2");
        assertThat(second.derived().get("url")).isEqualTo("https://y");
    }

    @Test
    void recoveredMandateWithUmrnIsActive() {
        bankOk();
        when(port.register(any())).thenThrow(new RuntimeException("timeout"));
        when(port.findByRef("4471-1")).thenReturn(Optional.of(
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

    @Test
    void zeroCeilingThrowsInsteadOfRegistering() {
        profile.setMonthlySalaryPaise(null);
        app.setSanctionedAmountPaise(null);
        assertThatThrownBy(() -> service.init(1L)).isInstanceOf(BusinessException.class);
        verify(port, never()).register(any());
    }

    @Test
    void doesNotReuseRevokedMandateThatKeepsItsUmrn() {
        Mandate revoked = active("9012", 20_000_00L);
        revoked.setStatus(Mandate.Status.AUTHORIZATION_FAILED); // filter must still refuse a non-ACTIVE row
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

    @Test
    void reusesOlderActiveMandateHiddenBehindNewerRevokedOne() {
        active("9012", 20_000_00L);
        Mandate revoked = new Mandate();
        revoked.setUmrn("UMRN1");
        revoked.setStatus(Mandate.Status.AUTHORIZATION_FAILED);
        org.mockito.Mockito.lenient().when(mandates.findFirstByCustomerIdAndUmrnIsNotNullOrderByIdDesc(4471L))
                .thenReturn(Optional.of(revoked));
        assertThat(service.init(1L).status()).isEqualTo("PASS");
        verify(port, never()).register(any());
        verify(mandates, never()).findFirstByCustomerIdAndUmrnIsNotNullOrderByIdDesc(any()); // the hiding finder
    }

    @Test
    void activeMandateIgnoresRevokedMandateThatKeepsItsUmrn() {
        Mandate older = active("9012", 20_000_00L);
        Mandate revoked = new Mandate();
        revoked.setUmrn("UMRN1");
        revoked.setStatus(Mandate.Status.AUTHORIZATION_FAILED);
        org.mockito.Mockito.lenient().when(mandates.findFirstByCustomerIdAndUmrnIsNotNullOrderByIdDesc(4471L))
                .thenReturn(Optional.of(revoked));
        assertThat(service.activeMandate(4471L)).containsSame(older);
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
        when(mandates.findFirstByCustomerIdOrderByAttemptNoDesc(4471L)).thenReturn(Optional.of(m));

        MandateService.MandateView v = service.staffView(1L).orElseThrow();

        assertThat(v.accountLast4()).isEqualTo("9012");
        assertThat(v.overridable()).isTrue();
        assertThat(v.toString()).doesNotContain("123456789012");
        assertThat(service.isBankUnsupported(4471L)).isTrue();

        m.setStatus(Mandate.Status.AUTHORIZATION_FAILED);
        assertThat(service.staffView(1L).orElseThrow().overridable()).isFalse();
        assertThat(service.isBankUnsupported(4471L)).isFalse();
    }
}
