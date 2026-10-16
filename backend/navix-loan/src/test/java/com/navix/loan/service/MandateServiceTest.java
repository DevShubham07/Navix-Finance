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
}
