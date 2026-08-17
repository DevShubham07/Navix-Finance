package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.collections.SettlementDirectory;
import com.navix.common.notification.event.SettlementApprovedEvent;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.domain.DsaCommissionStatus;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.DsaCommission;
import com.navix.loan.entity.Lead;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.DsaCommissionEventRepository;
import com.navix.loan.repository.DsaCommissionRepository;
import com.navix.loan.repository.LeadRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The accrual/maturity/void engine. Exercises the exact-paise commission math, the PAN-driven
 * (never mobile-driven) attribution guard, the lead-before-application guard, the "second loan
 * creates nothing" guard, and the three status transitions (PAYABLE on clean close, VOID on
 * settlement / default / write-off).
 */
@ExtendWith(MockitoExtension.class)
class DsaCommissionServiceTest {

    @Mock private DsaCommissionRepository commissionRepository;
    @Mock private DsaCommissionEventRepository commissionEventRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private CustomerProfileRepository customerProfileRepository;
    @Mock private LoanRepository loanRepository;
    @Mock private LoanApplicationRepository applicationRepository;
    @Mock private SettlementDirectory settlementDirectory;

    private DsaCommissionService service() {
        return new DsaCommissionService(commissionRepository, commissionEventRepository, leadRepository,
                customerProfileRepository, loanRepository, applicationRepository, settlementDirectory);
    }

    private static LoanApplication app(long id, long customerId, Instant createdAt) {
        LoanApplication a = new LoanApplication();
        a.setId(id);
        a.setCustomerId(customerId);
        a.setCreatedAt(createdAt);
        a.setStatus(ApplicationStatus.ACTIVE);
        return a;
    }

    private static Loan loan(long id, long netDisbursedPaise) {
        Loan l = new Loan();
        l.setId(id);
        l.setNetDisbursed(netDisbursedPaise);
        return l;
    }

    private static Lead dsaLead(long id, long dsaId, String pan, Instant createdAt) {
        Lead l = new Lead();
        l.setId(id);
        l.setOwnerDsaId(dsaId);
        l.setPan(pan);
        l.setCreatedAt(createdAt);
        return l;
    }

    private static CustomerProfile profile(String pan, String mobile) {
        CustomerProfile p = new CustomerProfile();
        p.setPan(pan);
        p.setMobile(mobile);
        return p;
    }

    @Test
    void accruesExactlyThreePointFivePercentOfNetDisbursed_inPaise() {
        Instant leadCreated = Instant.parse("2026-01-01T00:00:00Z");
        LoanApplication a = app(10L, 500L, leadCreated.plusSeconds(60));
        Loan l = loan(20L, 1_000_000L); // ₹10,000 net disbursed

        when(customerProfileRepository.findByApplicationId(10L))
                .thenReturn(Optional.of(profile("ABCDE1234F", "9000000000")));
        when(leadRepository.findByPanAndOwnerDsaIdNotNull("ABCDE1234F"))
                .thenReturn(Optional.of(dsaLead(1L, 99L, "ABCDE1234F", leadCreated)));
        when(commissionRepository.findByLeadId(1L)).thenReturn(Optional.empty());
        when(loanRepository.countByCustomerId(500L)).thenReturn(1L);

        service().onLoanDisbursed(a, l);

        ArgumentCaptor<DsaCommission> captor = ArgumentCaptor.forClass(DsaCommission.class);
        verify(commissionRepository).save(captor.capture());
        DsaCommission saved = captor.getValue();
        assertThat(saved.getNetDisbursedPaise()).isEqualTo(1_000_000L);
        assertThat(saved.getRateBps()).isEqualTo(350);
        assertThat(saved.getAmountPaise()).isEqualTo(35_000L); // 1,000,000 * 350 / 10,000
        assertThat(saved.getStatus()).isEqualTo(DsaCommissionStatus.ACCRUED);
        assertThat(saved.getDsaStaffId()).isEqualTo(99L);
    }

    @Test
    void panMatchDrivesAttribution_mobileChangeDoesNotBreakIt() {
        Instant leadCreated = Instant.parse("2026-01-01T00:00:00Z");
        LoanApplication a = app(10L, 500L, leadCreated.plusSeconds(60));
        Loan l = loan(20L, 1_000_000L);
        // The customer's mobile on file differs from whatever the lead had — attribution still
        // succeeds because it keys on PAN only.
        when(customerProfileRepository.findByApplicationId(10L))
                .thenReturn(Optional.of(profile("ABCDE1234F", "8888888888")));
        when(leadRepository.findByPanAndOwnerDsaIdNotNull("ABCDE1234F"))
                .thenReturn(Optional.of(dsaLead(1L, 99L, "ABCDE1234F", leadCreated)));
        when(commissionRepository.findByLeadId(1L)).thenReturn(Optional.empty());
        when(loanRepository.countByCustomerId(500L)).thenReturn(1L);

        service().onLoanDisbursed(a, l);

        verify(commissionRepository).save(any());
    }

    @Test
    void mobileMatchAloneWithDifferentPanCreatesNothing() {
        Instant leadCreated = Instant.parse("2026-01-01T00:00:00Z");
        LoanApplication a = app(10L, 500L, leadCreated.plusSeconds(60));
        Loan l = loan(20L, 1_000_000L);
        when(customerProfileRepository.findByApplicationId(10L))
                .thenReturn(Optional.of(profile("ZZZZZ9999Z", "9876543210")));
        // No DSA lead holds THIS pan, even though some lead might share the mobile.
        when(leadRepository.findByPanAndOwnerDsaIdNotNull("ZZZZZ9999Z")).thenReturn(Optional.empty());

        service().onLoanDisbursed(a, l);

        verify(commissionRepository, never()).save(any());
    }

    @Test
    void applicationCreatedBeforeTheLeadEarnsNoCommission() {
        Instant leadCreated = Instant.parse("2026-01-01T00:00:00Z");
        // The application predates the lead — it was already in flight.
        LoanApplication a = app(10L, 500L, leadCreated.minusSeconds(60));
        Loan l = loan(20L, 1_000_000L);
        when(customerProfileRepository.findByApplicationId(10L))
                .thenReturn(Optional.of(profile("ABCDE1234F", "9000000000")));
        when(leadRepository.findByPanAndOwnerDsaIdNotNull("ABCDE1234F"))
                .thenReturn(Optional.of(dsaLead(1L, 99L, "ABCDE1234F", leadCreated)));

        service().onLoanDisbursed(a, l);

        verify(commissionRepository, never()).save(any());
    }

    @Test
    void secondLoanForSameCustomerCreatesNoSecondRow() {
        Instant leadCreated = Instant.parse("2026-01-01T00:00:00Z");
        LoanApplication a = app(11L, 500L, leadCreated.plusSeconds(60));
        Loan l = loan(21L, 1_000_000L);
        when(customerProfileRepository.findByApplicationId(11L))
                .thenReturn(Optional.of(profile("ABCDE1234F", "9000000000")));
        when(leadRepository.findByPanAndOwnerDsaIdNotNull("ABCDE1234F"))
                .thenReturn(Optional.of(dsaLead(1L, 99L, "ABCDE1234F", leadCreated)));
        when(commissionRepository.findByLeadId(1L)).thenReturn(Optional.empty());
        // This customer already has two loans (a reborrow) — not their first.
        when(loanRepository.countByCustomerId(500L)).thenReturn(2L);

        service().onLoanDisbursed(a, l);

        verify(commissionRepository, never()).save(any());
    }

    @Test
    void anExistingCommissionForTheLeadBlocksASecondAccrual() {
        Instant leadCreated = Instant.parse("2026-01-01T00:00:00Z");
        LoanApplication a = app(12L, 501L, leadCreated.plusSeconds(60));
        Loan l = loan(22L, 1_000_000L);
        when(customerProfileRepository.findByApplicationId(12L))
                .thenReturn(Optional.of(profile("ABCDE1234F", "9000000000")));
        when(leadRepository.findByPanAndOwnerDsaIdNotNull("ABCDE1234F"))
                .thenReturn(Optional.of(dsaLead(1L, 99L, "ABCDE1234F", leadCreated)));
        when(commissionRepository.findByLeadId(1L)).thenReturn(Optional.of(new DsaCommission()));

        service().onLoanDisbursed(a, l);

        verify(commissionRepository, never()).save(any());
    }

    @Test
    void accrualNeverThrowsEvenOnUnexpectedFailure() {
        when(customerProfileRepository.findByApplicationId(10L)).thenThrow(new RuntimeException("boom"));
        LoanApplication a = app(10L, 500L, Instant.now());
        Loan l = loan(20L, 1_000_000L);

        // Must not propagate.
        service().onLoanDisbursed(a, l);
    }

    @Test
    void cleanCloseFlipsAccruedToPayable() {
        DsaCommission c = new DsaCommission();
        c.setId(1L);
        c.setLoanId(30L);
        c.setStatus(DsaCommissionStatus.ACCRUED);
        when(commissionRepository.findByLoanId(30L)).thenReturn(Optional.of(c));
        when(settlementDirectory.approvedSettlementAmount(30L)).thenReturn(Optional.empty());
        when(applicationRepository.findByLoanId(30L)).thenReturn(Optional.empty());

        service().onLoanClosed(30L);

        assertThat(c.getStatus()).isEqualTo(DsaCommissionStatus.PAYABLE);
        assertThat(c.getPayableAt()).isNotNull();
    }

    @Test
    void approvedSettlementVoidsOnClose() {
        DsaCommission c = new DsaCommission();
        c.setId(1L);
        c.setLoanId(31L);
        c.setStatus(DsaCommissionStatus.ACCRUED);
        when(commissionRepository.findByLoanId(31L)).thenReturn(Optional.of(c));
        when(settlementDirectory.approvedSettlementAmount(31L)).thenReturn(Optional.of(50_000L));

        service().onLoanClosed(31L);

        assertThat(c.getStatus()).isEqualTo(DsaCommissionStatus.VOID);
        assertThat(c.getVoidedAt()).isNotNull();
    }

    @Test
    void defaultedOrWrittenOffVoidsOnClose() {
        DsaCommission c = new DsaCommission();
        c.setId(1L);
        c.setLoanId(32L);
        c.setStatus(DsaCommissionStatus.ACCRUED);
        when(commissionRepository.findByLoanId(32L)).thenReturn(Optional.of(c));
        when(settlementDirectory.approvedSettlementAmount(32L)).thenReturn(Optional.empty());
        LoanApplication a = new LoanApplication();
        a.setStatus(ApplicationStatus.WRITTEN_OFF);
        when(applicationRepository.findByLoanId(32L)).thenReturn(Optional.of(a));

        service().onLoanClosed(32L);

        assertThat(c.getStatus()).isEqualTo(DsaCommissionStatus.VOID);
    }

    @Test
    void onLoanClosedIsIdempotent_onlyActsOnAccruedRows() {
        DsaCommission c = new DsaCommission();
        c.setId(1L);
        c.setLoanId(33L);
        c.setStatus(DsaCommissionStatus.PAYABLE); // already matured
        when(commissionRepository.findByLoanId(33L)).thenReturn(Optional.of(c));

        service().onLoanClosed(33L);

        assertThat(c.getStatus()).isEqualTo(DsaCommissionStatus.PAYABLE);
        verify(commissionRepository, never()).save(any());
    }

    @Test
    void settlementApprovedEventVoidsAnAccruedCommissionImmediately() {
        DsaCommission c = new DsaCommission();
        c.setId(1L);
        c.setLoanId(40L);
        c.setStatus(DsaCommissionStatus.ACCRUED);
        when(commissionRepository.findByLoanId(40L)).thenReturn(Optional.of(c));

        SettlementApprovedEvent event = new SettlementApprovedEvent(
                UUID.randomUUID(), UUID.randomUUID(), 40L, 500L, 50_000L, 7L, Instant.now());
        service().onSettlementApproved(event);

        assertThat(c.getStatus()).isEqualTo(DsaCommissionStatus.VOID);
        verify(commissionEventRepository).save(any());
    }
}
