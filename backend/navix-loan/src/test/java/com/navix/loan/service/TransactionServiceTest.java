package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.dto.LoanDtos.TransactionView;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private LoanRepository loanRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private LoanApplicationRepository applicationRepository;
    @Mock
    private CustomerProfileRepository profileRepository;
    @Mock
    private DocumentStoragePort storage;

    private TransactionService service;

    @BeforeEach
    void setUp() {
        service = new TransactionService(loanRepository, paymentRepository, applicationRepository, profileRepository, storage);
        // Only the proof-resolution test cares about the return value; a harmless default elsewhere.
        lenient().when(storage.presignDownload("loan/repayment-proof/1.jpg")).thenReturn("https://s3.example/signed?p=1");
    }

    private Loan loan2() {
        Loan loan = new Loan();
        loan.setId(2L);
        loan.setCustomerId(7L);
        loan.setNetDisbursed(882_000L);
        loan.setDisbursalTxnRef("UTR-OUT-1");
        loan.setDisbursedOn(LocalDate.of(2026, 5, 20));
        loan.setStatus(LoanStatus.ACTIVE);
        return loan;
    }

    private LoanApplication app5() {
        LoanApplication a = new LoanApplication();
        a.setId(5L);
        a.setLoanId(2L);
        a.setCustomerId(7L);
        return a;
    }

    private CustomerProfile profile5() {
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(5L);
        p.setFullName("Aman");
        p.setPan("ABCDE1234F");
        p.setMobile("9876543210");
        return p;
    }

    private Payment payment1() {
        Payment pay = new Payment();
        pay.setId(1L);
        pay.setLoanId(2L);
        pay.setAmount(100_000L);
        pay.setMethod(PaymentMethod.UPI);
        pay.setStatus(PaymentStatus.VERIFIED);
        pay.setTxnRef("PAY-IN-1");
        pay.setPaidOn(LocalDate.of(2026, 6, 20));
        pay.setProofUrl("loan/repayment-proof/1.jpg");
        return pay;
    }

    private void stubAll(List<Loan> loans, List<LoanApplication> apps, List<CustomerProfile> profiles, List<Payment> payments) {
        when(loanRepository.findAll()).thenReturn(loans);
        // A direction=OUTGOING/INCOMING query now short-circuits the other side entirely (no
        // findAll on payments, and no profile lookup at all when there are no loans), so these are lenient.
        lenient().when(applicationRepository.findByLoanIdIn(any())).thenReturn(apps);
        lenient().when(profileRepository.findByApplicationIdIn(any())).thenReturn(profiles);
        lenient().when(paymentRepository.findAll()).thenReturn(payments);
    }

    @Test
    void mapsDisbursalsAndRepaymentsWithFullBorrowerPan() {
        stubAll(List.of(loan2()), List.of(app5()), List.of(profile5()), List.of(payment1()));

        List<TransactionView> txns = service.listTransactions(null, null);

        assertThat(txns).hasSize(2);
        TransactionView disbursal = txns.stream().filter(t -> "DISBURSAL".equals(t.type())).findFirst().orElseThrow();
        TransactionView repayment = txns.stream().filter(t -> "REPAYMENT".equals(t.type())).findFirst().orElseThrow();

        assertThat(disbursal.direction()).isEqualTo("OUTGOING");
        assertThat(disbursal.amountPaise()).isEqualTo(882_000L);
        assertThat(disbursal.txnRef()).isEqualTo("UTR-OUT-1");
        assertThat(disbursal.borrowerName()).isEqualTo("Aman");
        assertThat(disbursal.pan()).isEqualTo("ABCDE1234F"); // staff-only ledger shows the full PAN

        assertThat(repayment.direction()).isEqualTo("INCOMING");
        assertThat(repayment.amountPaise()).isEqualTo(100_000L);
        assertThat(repayment.borrowerName()).isEqualTo("Aman");
        // The borrower-uploaded screenshot resolves to a presigned link wherever the ledger shows a
        // repayment's transaction id — a disbursal (nothing was ever uploaded against it) gets none.
        assertThat(repayment.proofUrl()).isEqualTo("https://s3.example/signed?p=1");
        assertThat(disbursal.proofUrl()).isNull();
    }

    @Test
    void directionFilterReturnsOnlyOutgoing() {
        stubAll(List.of(loan2()), List.of(app5()), List.of(profile5()), List.of(payment1()));

        List<TransactionView> txns = service.listTransactions(null, "OUTGOING");

        assertThat(txns).hasSize(1);
        assertThat(txns.get(0).type()).isEqualTo("DISBURSAL");
    }

    @Test
    void queryFiltersByBorrowerName() {
        stubAll(List.of(loan2()), List.of(app5()), List.of(profile5()), List.of(payment1()));

        assertThat(service.listTransactions("aman", null)).hasSize(2); // case-insensitive
        assertThat(service.listTransactions("zzz", null)).isEmpty();
    }

    @Test
    void dateRangeFiltersToTheStatementPeriod() {
        // Disbursal on 2026-05-20, repayment on 2026-06-20.
        stubAll(List.of(loan2()), List.of(app5()), List.of(profile5()), List.of(payment1()));

        // June only → just the repayment.
        List<TransactionView> june = service.listTransactions(
                null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        assertThat(june).hasSize(1);
        assertThat(june.get(0).type()).isEqualTo("REPAYMENT");

        // Inclusive bounds → both days included; an out-of-range window → empty.
        assertThat(service.listTransactions(null, null, LocalDate.of(2026, 5, 20), LocalDate.of(2026, 6, 20))).hasSize(2);
        assertThat(service.listTransactions(null, null, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))).isEmpty();
    }

    @Test
    void presignsOnlyRowsThatSurviveTheFilter() {
        Payment p1 = payment1(); // paidOn 2026-06-20, proof loan/repayment-proof/1.jpg (stubbed in setUp)
        Payment p2 = new Payment();
        p2.setId(2L);
        p2.setLoanId(2L);
        p2.setAmount(50_000L);
        p2.setMethod(PaymentMethod.UPI);
        p2.setStatus(PaymentStatus.VERIFIED);
        p2.setPaidOn(LocalDate.of(2026, 7, 1));
        p2.setProofUrl("loan/repayment-proof/2.jpg");
        Payment p3 = new Payment();
        p3.setId(3L);
        p3.setLoanId(2L);
        p3.setAmount(60_000L);
        p3.setMethod(PaymentMethod.UPI);
        p3.setStatus(PaymentStatus.VERIFIED);
        p3.setPaidOn(LocalDate.of(2026, 8, 1));
        p3.setProofUrl("loan/repayment-proof/3.jpg");
        stubAll(List.of(loan2()), List.of(app5()), List.of(profile5()), List.of(p1, p2, p3));

        // Window keeps only p1 (June); p2/p3 (July/August) are excluded before presigning ever runs.
        List<TransactionView> txns = service.listTransactions(
                null, "INCOMING", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(txns).hasSize(1);
        verify(storage, times(1)).presignDownload(any());
    }

    @Test
    void borrowersByLoanIdUsesLoanCustomerIdAndProfileName() {
        when(loanRepository.findAllById(List.of(2L))).thenReturn(List.of(loan2()));
        when(applicationRepository.findByLoanIdIn(List.of(2L))).thenReturn(List.of(app5()));
        when(profileRepository.findByApplicationIdIn(List.of(5L))).thenReturn(List.of(profile5()));

        Map<Long, TransactionService.BorrowerRef> refs = service.borrowersByLoanId(List.of(2L));

        assertThat(refs).hasSize(1);
        TransactionService.BorrowerRef ref = refs.get(2L);
        assertThat(ref.customerId()).isEqualTo(7L); // from the loan
        assertThat(ref.borrowerName()).isEqualTo("Aman"); // from the profile
    }

    @Test
    void profilesByLoanIdKeepsTheLastApplicationWithAProfile() {
        LoanApplication appNoProfile = new LoanApplication();
        appNoProfile.setId(10L);
        appNoProfile.setLoanId(2L);
        appNoProfile.setCustomerId(7L);

        LoanApplication appWithProfile = new LoanApplication();
        appWithProfile.setId(11L);
        appWithProfile.setLoanId(2L);
        appWithProfile.setCustomerId(7L);

        CustomerProfile profile = new CustomerProfile();
        profile.setApplicationId(11L);
        profile.setFullName("Rita");

        when(applicationRepository.findByLoanIdIn(List.of(2L))).thenReturn(List.of(appNoProfile, appWithProfile));
        when(profileRepository.findByApplicationIdIn(List.of(10L, 11L))).thenReturn(List.of(profile));

        Map<Long, CustomerProfile> result = service.profilesByLoanId(List.of(2L));

        assertThat(result).hasSize(1);
        assertThat(result.get(2L).getFullName()).isEqualTo("Rita");
    }
}
