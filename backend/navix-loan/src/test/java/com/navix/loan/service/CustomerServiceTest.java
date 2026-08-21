package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.CustomerDtos.CustomerSummary;
import com.navix.loan.dto.CustomerDtos.UpdateCustomerRequest;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.CustomerOwner;
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
    @Mock private com.navix.loan.repository.CustomerOwnerRepository ownerRepository;
    @Mock private com.navix.loan.repository.CustomerCallLogRepository callLogRepository;
    @Mock private com.navix.common.staff.StaffDirectory staffDirectory;
    @Mock private RiskPort risk;
    @Mock private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Mock private CreditBriefService creditBriefService;
    @Mock private com.navix.loan.repository.ApplicationDocumentRepository documentRepository;
    @Mock private BureauStateService bureauStateService;
    @Mock private com.navix.loan.repository.ApplicationVerificationRepository verificationRepository;
    @Mock private com.navix.loan.repository.ApplicationReferenceRepository referenceRepository;
    @Mock private com.navix.common.verification.OtpVerifierPort otpVerifier;

    private CustomerService service;

    @BeforeEach
    void setUp() {
        service = new CustomerService(applicationRepository, loanRepository, profileRepository,
                paymentRepository, repaymentService, changeLogRepository,
                applicationEventRepository, remarkRepository, ownerRepository, callLogRepository,
                staffDirectory, risk, jdbc, creditBriefService, documentRepository, bureauStateService,
                verificationRepository, referenceRepository, otpVerifier);
        lenient().when(ownerRepository.findAll()).thenReturn(List.of());
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
        // A Head sees the whole book (FULL_CUSTOMER_VIEW_ROLES); scoping is covered separately.
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
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
    void listFiltersByNamePanMobileOrCustomerId() {
        // A Head sees the whole book (FULL_CUSTOMER_VIEW_ROLES); scoping is covered separately.
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        when(applicationRepository.findAll()).thenReturn(List.of(
                app(1, 9000001L, ApplicationStatus.ACTIVE),
                app(2, 9000002L, ApplicationStatus.ACTIVE)));
        CustomerProfile asha = profile(1, "Asha Rao", "AAAAA1111A");
        asha.setMobile("9876543210");
        lenient().when(profileRepository.findByApplicationId(1L)).thenReturn(Optional.of(asha));
        lenient().when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(profile(2, "Bhavya Reddy", "BBBBB2222B")));
        lenient().when(loanRepository.findByCustomerId(any())).thenReturn(List.of());

        assertThat(service.list("asha")).extracting(CustomerSummary::customerId).containsExactly(9000001L);
        assertThat(service.list("aaaaa1111a")).extracting(CustomerSummary::customerId).containsExactly(9000001L);
        assertThat(service.list("9876543210")).extracting(CustomerSummary::customerId).containsExactly(9000001L);
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

    // ---------------------------------------------------------------- mobile-number correction (OTP)

    @Test
    void requestMobileChangeOtpRejectedForNonAdmin() {
        ActorContext.set(new CurrentActor("7", "Acc", "ACCOUNTANT"));
        assertThatThrownBy(() -> service.requestMobileChangeOtp(9000001L, "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void requestMobileChangeOtpRejectsAnInvalidMobile() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(app(2, 9000001L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(profile(2, "Asha", "ABCDE1234F")));

        assertThatThrownBy(() -> service.requestMobileChangeOtp(9000001L, "12345"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("valid 10-digit");
        verify(otpVerifier, org.mockito.Mockito.never()).request(anyString(), anyString());
    }

    @Test
    void requestMobileChangeOtpSendsToTheNewNumber() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(app(2, 9000001L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(profile(2, "Asha", "ABCDE1234F")));
        var expected = new com.navix.common.verification.OtpVerifierPort.OtpRequestResult(true, null, 300);
        when(otpVerifier.request("9876543210", com.navix.common.verification.OtpVerifierPort.ADMIN_MOBILE_CHANGE))
                .thenReturn(expected);

        var result = service.requestMobileChangeOtp(9000001L, "9876543210");

        assertThat(result.sent()).isTrue();
        verify(otpVerifier).request("9876543210", com.navix.common.verification.OtpVerifierPort.ADMIN_MOBILE_CHANGE);
    }

    @Test
    void confirmMobileChangeRejectsAnInvalidOtpAndLeavesTheProfileUntouched() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        CustomerProfile p = profile(2, "Asha", "ABCDE1234F");
        p.setMobile("9000000000");
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(app(2, 9000001L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(p));
        when(otpVerifier.verify(eq("9876543210"), anyString(),
                eq(com.navix.common.verification.OtpVerifierPort.ADMIN_MOBILE_CHANGE))).thenReturn(false);

        assertThatThrownBy(() -> service.confirmMobileChange(9000001L, "9876543210", "000000"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid or expired");
        assertThat(p.getMobile()).isEqualTo("9000000000"); // untouched
        verify(profileRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void confirmMobileChangeUpdatesTheProfileAndLogsTheChange() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        CustomerProfile p = profile(2, "Asha", "ABCDE1234F");
        p.setMobile("9000000000");
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(app(2, 9000001L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(p));
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(otpVerifier.verify(eq("9876543210"), eq("123456"),
                eq(com.navix.common.verification.OtpVerifierPort.ADMIN_MOBILE_CHANGE))).thenReturn(true);

        service.confirmMobileChange(9000001L, "9876543210", "123456");

        assertThat(p.getMobile()).isEqualTo("9876543210");
        assertThat(p.getPan()).isEqualTo("ABCDE1234F"); // identity untouched
        verify(changeLogRepository).save(any());
    }

    // ---------------------------------------------------------------- sanctioned-amount correction (OTP)

    @Test
    void requestSanctionedAmountOtpRejectedForNonAdmin() {
        ActorContext.set(new CurrentActor("7", "Acc", "ACCOUNTANT"));
        assertThatThrownBy(() -> service.requestSanctionedAmountOtp(9000001L, 500_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void requestSanctionedAmountOtpRejectsWhenNothingIsSanctionedAndUndisbursed() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        // Sanctioned but already disbursed — must NOT be a correction target.
        LoanApplication disbursed = app(2, 9000001L, ApplicationStatus.ACTIVE);
        disbursed.setSanctionedAmountPaise(500_000L);
        disbursed.setLoanId(77L);
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(disbursed));

        assertThatThrownBy(() -> service.requestSanctionedAmountOtp(9000001L, 600_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no sanctioned application");
    }

    @Test
    void requestSanctionedAmountOtpRejectsBelowTheMinimum() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        LoanApplication a = app(2, 9000001L, ApplicationStatus.SANCTIONED);
        a.setSanctionedAmountPaise(500_000L);
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(a));

        assertThatThrownBy(() -> service.requestSanctionedAmountOtp(9000001L, 50_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("below the minimum");
    }

    @Test
    void requestSanctionedAmountOtpRejectsBelowWhatTheBorrowerAlreadyChoseToDraw() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        LoanApplication a = app(2, 9000001L, ApplicationStatus.SANCTIONED);
        a.setSanctionedAmountPaise(500_000L);
        a.setAmountRequested(400_000L);
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(a));

        assertThatThrownBy(() -> service.requestSanctionedAmountOtp(9000001L, 300_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already chose to draw");
    }

    @Test
    void requestSanctionedAmountOtpSendsToTheCustomersExistingMobileNotTheRequestBody() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        LoanApplication a = app(2, 9000001L, ApplicationStatus.SANCTIONED);
        a.setSanctionedAmountPaise(500_000L);
        CustomerProfile p = profile(2, "Asha", "ABCDE1234F");
        p.setMobile("9000000000");
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(a));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(p));
        var expected = new com.navix.common.verification.OtpVerifierPort.OtpRequestResult(true, null, 300);
        when(otpVerifier.request("9000000000", com.navix.common.verification.OtpVerifierPort.ADMIN_AMOUNT_CHANGE))
                .thenReturn(expected);

        service.requestSanctionedAmountOtp(9000001L, 700_000L);

        verify(otpVerifier).request("9000000000", com.navix.common.verification.OtpVerifierPort.ADMIN_AMOUNT_CHANGE);
    }

    @Test
    void confirmSanctionedAmountChangeUpdatesTheAmountWithoutTouchingStatus() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        LoanApplication a = app(2, 9000001L, ApplicationStatus.SANCTIONED);
        a.setSanctionedAmountPaise(500_000L);
        CustomerProfile p = profile(2, "Asha", "ABCDE1234F");
        p.setMobile("9000000000");
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(a));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(p));
        when(applicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(otpVerifier.verify(eq("9000000000"), eq("123456"),
                eq(com.navix.common.verification.OtpVerifierPort.ADMIN_AMOUNT_CHANGE))).thenReturn(true);

        service.confirmSanctionedAmountChange(9000001L, 700_000L, "123456");

        assertThat(a.getSanctionedAmountPaise()).isEqualTo(700_000L);
        assertThat(a.getStatus()).isEqualTo(ApplicationStatus.SANCTIONED); // status untouched
        verify(changeLogRepository).save(any());
    }

    @Test
    void deleteCustomerRejectedForNonAdmin() {
        ActorContext.set(new CurrentActor("7", "Acc", "ACCOUNTANT"));
        assertThatThrownBy(() -> service.deleteCustomer(9000001L))
                .hasMessageContaining("ADMIN");
        // requireAdmin() fires before any DB work.
        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }

    @Test
    void deleteCustomerCascadesForAdmin() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        // single-arg deletes return 1 (the two-arg referral deletes fall through to 0 — fine here).
        lenient().when(jdbc.update(anyString(), any(Object.class))).thenReturn(1);

        CustomerService.DeletionResult res = service.deleteCustomer(9000001L);

        assertThat(res.customerId()).isEqualTo(9000001L);
        assertThat(res.totalRows()).isGreaterThan(0);
    }

    @Test
    void assignOwner_asTelecaller_acceptsAnotherTelecaller() {
        givenAssignableCustomer("21", "Caller", "TELECALLER");
        when(staffDirectory.findStaff(22L))
                .thenReturn(Optional.of(new StaffSummary(22L, "Colleague", "TELECALLER", true)));

        service.assignOwner(9000001L, 22L);

        verify(ownerRepository).save(org.mockito.ArgumentMatchers.argThat(owner ->
                owner.getCustomerId().equals(9000001L) && owner.getOwnerStaffId().equals(22L)));
    }

    @Test
    void assignOwner_asTelecaller_acceptsSelf() {
        givenAssignableCustomer("21", "Caller", "TELECALLER");
        when(staffDirectory.findStaff(21L))
                .thenReturn(Optional.of(new StaffSummary(21L, "Caller", "TELECALLER", true)));

        service.assignOwner(9000001L, 21L);

        verify(ownerRepository).save(any(CustomerOwner.class));
    }

    @Test
    void assignOwner_asTelecaller_acceptsUnallocation() {
        givenAssignableCustomer("21", "Caller", "TELECALLER");
        CustomerOwner existing = new CustomerOwner();
        existing.setCustomerId(9000001L);
        existing.setOwnerStaffId(22L);
        when(ownerRepository.findById(9000001L)).thenReturn(Optional.of(existing));

        service.assignOwner(9000001L, null);

        verify(ownerRepository).deleteById(9000001L);
    }

    @Test
    void assignOwner_asTelecaller_rejectsCreditHeadTarget() {
        givenAssignableCustomer("21", "Caller", "TELECALLER");
        when(staffDirectory.findStaff(30L))
                .thenReturn(Optional.of(new StaffSummary(30L, "Credit Head", "CREDIT_HEAD", true)));

        assertThatThrownBy(() -> service.assignOwner(9000001L, 30L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("FORBIDDEN_ROLE");
    }

    @Test
    void assignOwner_asCreditHead_keepsAllowingOtherActiveRoles() {
        givenAssignableCustomer("31", "Credit Head", "CREDIT_HEAD");
        when(staffDirectory.findStaff(30L))
                .thenReturn(Optional.of(new StaffSummary(30L, "Another Head", "CREDIT_HEAD", true)));

        service.assignOwner(9000001L, 30L);

        verify(ownerRepository).save(any(CustomerOwner.class));
    }

    @Test
    void assignOwner_asAdmin_keepsAllowingOtherActiveRoles() {
        givenAssignableCustomer("1", "Admin", "ADMIN");
        when(staffDirectory.findStaff(30L))
                .thenReturn(Optional.of(new StaffSummary(30L, "Credit Head", "CREDIT_HEAD", true)));

        service.assignOwner(9000001L, 30L);

        verify(ownerRepository).save(any(CustomerOwner.class));
    }


    // --- Customers-list scoping -------------------------------------------------------------
    // Heads + ADMIN see the whole book; everyone else is scoped to customers they own work on.
    // The list filter alone is not enough — detail() must refuse out-of-scope ids too, or the
    // scope is bypassable by typing a URL.

    private ApplicationEvent decision(long applicationId, String actorId, String action) {
        ApplicationEvent e = new ApplicationEvent();
        e.setApplicationId(applicationId);
        e.setActorId(actorId);
        e.setAction(action);
        e.setToStatus(ApplicationStatus.SANCTIONED);
        return e;
    }

    /** Two customers on file; the executive is assigned 9000001 and has decided nothing. */
    private void givenTwoCustomers() {
        when(applicationRepository.findAll()).thenReturn(List.of(
                app(1, 9000001L, ApplicationStatus.ACTIVE),
                app(2, 9000002L, ApplicationStatus.ACTIVE)));
        lenient().when(profileRepository.findByApplicationId(1L))
                .thenReturn(Optional.of(profile(1, "Asha Rao", "AAAAA1111A")));
        lenient().when(profileRepository.findByApplicationId(2L))
                .thenReturn(Optional.of(profile(2, "Bhavya Reddy", "BBBBB2222B")));
        lenient().when(loanRepository.findByCustomerId(any())).thenReturn(List.of());
    }

    @Test
    void list_asCreditExecutive_showsOnlyAssignedAndDecidedCustomers() {
        ActorContext.set(new CurrentActor("12", "Exec", "CREDIT_EXECUTIVE"));
        givenTwoCustomers();
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(12L))
                .thenReturn(java.util.Set.of(9000001L));
        when(applicationEventRepository.findByActorIdOrderByAtDesc("12")).thenReturn(List.of());

        assertThat(service.list(null)).extracting(CustomerSummary::customerId)
                .containsExactly(9000001L);
    }

    @Test
    void list_asCreditExecutive_includesCustomersTheyDecidedOn() {
        ActorContext.set(new CurrentActor("12", "Exec", "CREDIT_EXECUTIVE"));
        givenTwoCustomers();
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(12L)).thenReturn(java.util.Set.of());
        when(applicationEventRepository.findByActorIdOrderByAtDesc("12"))
                .thenReturn(List.of(decision(2L, "12", "SANCTION")));
        when(applicationRepository.findCustomerIdsByIdIn(List.of(2L))).thenReturn(java.util.Set.of(9000002L));

        assertThat(service.list(null)).extracting(CustomerSummary::customerId)
                .containsExactly(9000002L);
    }

    @Test
    void list_ignoresNonDecisionEventsWhenScoping() {
        ActorContext.set(new CurrentActor("12", "Exec", "CREDIT_EXECUTIVE"));
        givenTwoCustomers();
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(12L)).thenReturn(java.util.Set.of());
        // Merely creating/submitting an application is routing noise, not a decision.
        when(applicationEventRepository.findByActorIdOrderByAtDesc("12"))
                .thenReturn(List.of(decision(2L, "12", "CREATE")));

        assertThat(service.list(null)).isEmpty();
    }

    @Test
    void list_asCreditHead_showsEveryCustomer() {
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        givenTwoCustomers();

        assertThat(service.list(null)).extracting(CustomerSummary::customerId)
                .containsExactlyInAnyOrder(9000001L, 9000002L);
    }

    @Test
    void list_asTelecaller_alsoSeesUnallocatedCustomers() {
        ActorContext.set(new CurrentActor("21", "Caller", "TELECALLER"));
        givenTwoCustomers();
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(21L)).thenReturn(java.util.Set.of());
        when(applicationEventRepository.findByActorIdOrderByAtDesc("21")).thenReturn(List.of());
        // 9000002 is claimed by someone else; 9000001 has NO owner row at all, which is what
        // "unallocated" looks like in practice — so it must still be visible to a telecaller.
        CustomerOwner claimed = new CustomerOwner();
        claimed.setCustomerId(9000002L);
        claimed.setOwnerStaffId(99L);
        when(ownerRepository.findAll()).thenReturn(List.of(claimed));
        lenient().when(staffDirectory.findStaff(99L))
                .thenReturn(Optional.of(new StaffSummary(99L, "Other", "TELECALLER", true)));

        assertThat(service.list(null)).extracting(CustomerSummary::customerId)
                .containsExactly(9000001L);
    }

    @Test
    void list_asTelecaller_keepsCustomersTheyHaveClaimed() {
        // Claiming a lead allocates it — and the unallocated rule is an inversion, so without an
        // explicit carve-out a telecaller would lose sight of a customer the moment they took it.
        ActorContext.set(new CurrentActor("21", "Caller", "TELECALLER"));
        givenTwoCustomers();
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(21L)).thenReturn(java.util.Set.of());
        when(applicationEventRepository.findByActorIdOrderByAtDesc("21")).thenReturn(List.of());
        CustomerOwner mine = new CustomerOwner();
        mine.setCustomerId(9000001L);
        mine.setOwnerStaffId(21L);
        CustomerOwner theirs = new CustomerOwner();
        theirs.setCustomerId(9000002L);
        theirs.setOwnerStaffId(99L);
        when(ownerRepository.findAll()).thenReturn(List.of(mine, theirs));
        lenient().when(staffDirectory.findStaff(any()))
                .thenReturn(Optional.of(new StaffSummary(21L, "Caller", "TELECALLER", true)));

        assertThat(service.list(null)).extracting(CustomerSummary::customerId)
                .containsExactly(9000001L);
    }

    @Test
    void detail_outOfScope_is404NotForbidden() {
        // 404 rather than 403 on purpose: a 403 would confirm the customer exists, turning the
        // endpoint into an enumeration oracle over PII.
        ActorContext.set(new CurrentActor("12", "Exec", "CREDIT_EXECUTIVE"));
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(12L)).thenReturn(java.util.Set.of());
        when(applicationEventRepository.findByActorIdOrderByAtDesc("12")).thenReturn(List.of());

        assertThatThrownBy(() -> service.detail(9000002L))
                .isInstanceOf(com.navix.common.exception.ResourceNotFoundException.class);
    }

    @Test
    void remarks_outOfScope_areRefused() {
        // The sibling per-customer reads are reachable by id through the same proxy, so scoping the
        // list alone would be cosmetic.
        ActorContext.set(new CurrentActor("12", "Exec", "CREDIT_EXECUTIVE"));
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(12L)).thenReturn(java.util.Set.of());
        when(applicationEventRepository.findByActorIdOrderByAtDesc("12")).thenReturn(List.of());

        assertThatThrownBy(() -> service.remarks(9000002L))
                .isInstanceOf(com.navix.common.exception.ResourceNotFoundException.class);
    }

    // --- Date window ------------------------------------------------------------------------

    @Test
    void list_dateWindowIsResolvedInIst() {
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        LoanApplication late = app(1, 9000001L, ApplicationStatus.ACTIVE);
        // 23:30 IST on 2026-08-19 — still 18:00 UTC the same day. Resolving the window in UTC
        // would push this row into the 20th and drop it from a "Today = 19th" filter.
        late.setCreatedAt(java.time.LocalDateTime.of(2026, 8, 19, 23, 30)
                .atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant());
        when(applicationRepository.findAll()).thenReturn(List.of(late));
        lenient().when(profileRepository.findByApplicationId(1L))
                .thenReturn(Optional.of(profile(1, "Asha Rao", "AAAAA1111A")));
        lenient().when(loanRepository.findByCustomerId(any())).thenReturn(List.of());

        java.time.LocalDate d19 = java.time.LocalDate.of(2026, 8, 19);
        java.time.LocalDate d20 = java.time.LocalDate.of(2026, 8, 20);
        assertThat(service.list(null, d19, d19)).hasSize(1);
        assertThat(service.list(null, d20, d20)).isEmpty();
    }

    private void givenAssignableCustomer(String actorId, String actorName, String actorRole) {
        ActorContext.set(new CurrentActor(actorId, actorName, actorRole));
        LoanApplication application = app(1, 9000001L, ApplicationStatus.ACTIVE);
        CustomerProfile customerProfile = profile(1, "Asha Rao", "ABCDE1234F");
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(application));
        when(profileRepository.findByApplicationId(1L)).thenReturn(Optional.of(customerProfile));
        when(loanRepository.findByCustomerId(9000001L)).thenReturn(List.of());
    }
}
