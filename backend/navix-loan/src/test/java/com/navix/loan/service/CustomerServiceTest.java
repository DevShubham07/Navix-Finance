package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.CustomerDtos.CustomerSummary;
import com.navix.loan.dto.CustomerDtos.UpdateCustomerRequest;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.common.risk.RiskPort;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.repository.ProfileChangeLogRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock private LoanApplicationRepository applicationRepository;
    @Mock private LoanRepository loanRepository;
    @Mock private CustomerProfileRepository profileRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private RepaymentService repaymentService;
    @Mock private ProfileChangeLogRepository changeLogRepository;
    @Mock private com.navix.loan.repository.ApplicationEventRepository applicationEventRepository;
    @Mock private com.navix.loan.repository.CustomerRemarkRepository remarkRepository;
    @Mock private RiskPort risk;

    private CustomerService service;

    @BeforeEach
    void setUp() {
        service = new CustomerService(applicationRepository, loanRepository, profileRepository,
                paymentRepository, repaymentService, changeLogRepository,
                applicationEventRepository, remarkRepository, risk);
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private LoanApplication app(long id, long customerId, ApplicationStatus status) {
        LoanApplication a = new LoanApplication();
        a.setId(id);
        a.setCustomerId(customerId);
        a.setStatus(status);
        return a;
    }

    private CustomerProfile profile(long applicationId, String name, String pan) {
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(applicationId);
        p.setFullName(name);
        p.setPan(pan);
        p.setEmployer("Acme");
        p.setMonthlySalaryPaise(4_000_000L);
        return p;
    }

    @Test
    void listGroupsByCustomerPicksLatestProfileAndShowsFullPan() {
        // Customer 9000001 has two applications; the newer (id 2) carries the current name.
        when(applicationRepository.findAll()).thenReturn(List.of(
                app(1, 9000001L, ApplicationStatus.CLOSED),
                app(2, 9000001L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(profile(2, "Asha Rao", "ABCDE1234F")));
        lenient().when(profileRepository.findByApplicationId(1L)).thenReturn(Optional.of(profile(1, "Old Name", "ABCDE1234F")));
        when(loanRepository.findByCustomerId(9000001L)).thenReturn(List.of());

        List<CustomerSummary> rows = service.list(null);

        assertThat(rows).hasSize(1);
        CustomerSummary cs = rows.get(0);
        assertThat(cs.customerId()).isEqualTo(9000001L);
        assertThat(cs.name()).isEqualTo("Asha Rao");           // from the latest application's profile
        assertThat(cs.applicationCount()).isEqualTo(2);
        assertThat(cs.latestStatus()).isEqualTo("ACTIVE");      // newest application's status
        assertThat(cs.pan()).isEqualTo("ABCDE1234F");           // staff see the full, unmasked PAN
    }

    @Test
    void listFiltersByNameOrCustomerId() {
        when(applicationRepository.findAll()).thenReturn(List.of(
                app(1, 9000001L, ApplicationStatus.ACTIVE),
                app(2, 9000002L, ApplicationStatus.ACTIVE)));
        lenient().when(profileRepository.findByApplicationId(1L)).thenReturn(Optional.of(profile(1, "Asha Rao", "AAAAA1111A")));
        lenient().when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(profile(2, "Bhavya Reddy", "BBBBB2222B")));
        lenient().when(loanRepository.findByCustomerId(any())).thenReturn(List.of());

        assertThat(service.list("asha")).extracting(CustomerSummary::customerId).containsExactly(9000001L);
        assertThat(service.list("9000002")).extracting(CustomerSummary::customerId).containsExactly(9000002L);
        assertThat(service.list("")).hasSize(2);
    }

    @Test
    void updateProfileRejectedForNonAdmin() {
        ActorContext.set(new CurrentActor("7", "Acc", "ACCOUNTANT"));
        assertThatThrownBy(() -> service.updateProfile(9000001L,
                new UpdateCustomerRequest("New Name", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void updateProfileEditsLatestProfileForAdmin() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        CustomerProfile p = profile(2, "Asha Rao", "ABCDE1234F");
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(app(2, 9000001L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(p));
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.updateProfile(9000001L,
                new UpdateCustomerRequest("Asha R. Rao", "12 MG Road", "Globex", "SALARIED", 6_000_000L, null, null, null, "HDFC"));

        assertThat(p.getFullName()).isEqualTo("Asha R. Rao");
        assertThat(p.getEmployer()).isEqualTo("Globex");
        assertThat(p.getMonthlySalaryPaise()).isEqualTo(6_000_000L);
        assertThat(p.getPan()).isEqualTo("ABCDE1234F");          // identity untouched
    }

    @Test
    void salaryEditLogsChangeAndRecomputesEligibilityForPreDisbursementApp() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        CustomerProfile p = profile(2, "Asha", "ABCDE1234F");
        p.setMonthlySalaryPaise(5_000_000L);
        LoanApplication a = app(2, 9000001L, ApplicationStatus.KYC_APPROVED); // loanId null = pre-disbursement
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(a));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(p));
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(risk.eligibleLimitPaise(6_000_000L)).thenReturn(1_500_000L);

        service.updateProfile(9000001L,
                new UpdateCustomerRequest("Asha", null, null, null, 6_000_000L, null, null, null, null));

        verify(changeLogRepository, atLeastOnce()).save(any());   // the salary change is recorded
        assertThat(a.getEligibleLimit()).isEqualTo(1_500_000L);   // eligibility recomputed from new salary
    }
}
