package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.dto.LoanDtos.TransactionView;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.ApplicantProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.LocalDate;
import java.util.List;
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
    private ApplicantProfileRepository profileRepository;

    private TransactionService service;

    @BeforeEach
    void setUp() {
        service = new TransactionService(loanRepository, paymentRepository, applicationRepository, profileRepository);
    }

    private Loan loan2() {
        Loan loan = new Loan();
        loan.setId(2L);
        loan.setApplicantId(7L);
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
        a.setApplicantId(7L);
        return a;
    }

    private ApplicantProfile profile5() {
        ApplicantProfile p = new ApplicantProfile();
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
        return pay;
    }

    private void stubAll(List<Loan> loans, List<LoanApplication> apps, List<ApplicantProfile> profiles, List<Payment> payments) {
        when(loanRepository.findAll()).thenReturn(loans);
        when(applicationRepository.findAll()).thenReturn(apps);
        when(profileRepository.findAll()).thenReturn(profiles);
        when(paymentRepository.findAll()).thenReturn(payments);
    }

    @Test
    void mapsDisbursalsAndRepaymentsWithMaskedBorrower() {
        stubAll(List.of(loan2()), List.of(app5()), List.of(profile5()), List.of(payment1()));

        List<TransactionView> txns = service.listTransactions(null, null);

        assertThat(txns).hasSize(2);
        TransactionView disbursal = txns.stream().filter(t -> "DISBURSAL".equals(t.type())).findFirst().orElseThrow();
        TransactionView repayment = txns.stream().filter(t -> "REPAYMENT".equals(t.type())).findFirst().orElseThrow();

        assertThat(disbursal.direction()).isEqualTo("OUTGOING");
        assertThat(disbursal.amountPaise()).isEqualTo(882_000L);
        assertThat(disbursal.txnRef()).isEqualTo("UTR-OUT-1");
        assertThat(disbursal.borrowerName()).isEqualTo("Aman");
        assertThat(disbursal.panMasked()).isNotEqualTo("ABCDE1234F"); // masked, never raw

        assertThat(repayment.direction()).isEqualTo("INCOMING");
        assertThat(repayment.amountPaise()).isEqualTo(100_000L);
        assertThat(repayment.borrowerName()).isEqualTo("Aman");
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
}
