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
import com.navix.loan.entity.CustomerCallLog;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.CustomerOwner;
import com.navix.loan.entity.LoanApplication;
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
    @Mock private com.navix.loan.repository.CustomerLimitOverrideRepository limitOverrideRepository;
    @Mock private EligibilityService eligibilityService;
    @Mock private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Mock private CreditBriefService creditBriefService;
    @Mock private com.navix.loan.repository.ApplicationDocumentRepository documentRepository;
    @Mock private BureauStateService bureauStateService;
    @Mock private VerificationFailureService verificationFailureService;
    @Mock private com.navix.common.verification.ProviderAttemptDirectory providerAttempts;
    @Mock private com.navix.loan.repository.ApplicationVerificationRepository verificationRepository;
    @Mock private com.navix.loan.repository.ApplicationReferenceRepository referenceRepository;
    @Mock private com.navix.common.verification.OtpVerifierPort otpVerifier;
    @Mock private com.navix.common.security.BorrowerIdentityPort borrowerIdentity;
    @Mock private com.navix.common.loan.ApplicationActorDirectory applicationActorDirectory;
    @Mock private com.navix.common.collections.CollectionCaseDirectory collectionCaseDirectory;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private LoanMath loanMath;
    @Mock private CustomerBookQuery bookQuery;

    private CustomerService service;

    @BeforeEach
    void setUp() {
        service = new CustomerService(applicationRepository, loanRepository, profileRepository,
                paymentRepository, repaymentService, changeLogRepository,
                applicationEventRepository, remarkRepository, ownerRepository,
                limitOverrideRepository, eligibilityService, callLogRepository,
                staffDirectory, applicationActorDirectory, collectionCaseDirectory,
                jdbc, creditBriefService, documentRepository, bureauStateService,
                verificationFailureService, providerAttempts, verificationRepository, referenceRepository, otpVerifier, borrowerIdentity, eventPublisher,
                loanMath, bookQuery);
        lenient().when(ownerRepository.findAll()).thenReturn(List.of());
        // Nothing outstanding by default: an unstubbed mock returns null, and the summary
        // dereferences the reason. Tests about a specific failure stub this themselves.
        lenient().when(verificationFailureService.failures(any())).thenReturn(java.util.Map.of());
        // No collision by default — the handful of tests that DO care about this stub it explicitly.
        lenient().when(borrowerIdentity.wouldCollideWithAnotherCustomer(anyString(), any())).thenReturn(false);
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
    void byIdsGroupsByCustomerPicksLatestProfileAndShowsFullPan() {
        // A Head sees the whole book (FULL_CUSTOMER_VIEW_ROLES); scoping is covered separately.
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        // Customer 9000001 has two applications; the newer (id 2) carries the current name.
        when(applicationRepository.findByCustomerIdIn(List.of(9000001L))).thenReturn(List.of(
                app(1, 9000001L, ApplicationStatus.CLOSED),
                app(2, 9000001L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationIdIn(any())).thenReturn(List.of(
                profile(1, "Old Name", "ABCDE1234F"), profile(2, "Asha Rao", "ABCDE1234F")));

        List<CustomerSummary> rows = service.byIds(List.of(9000001L));

        assertThat(rows).hasSize(1);
        CustomerSummary cs = rows.get(0);
        assertThat(cs.customerId()).isEqualTo(9000001L);
        assertThat(cs.name()).isEqualTo("Asha Rao");           // from the latest application's profile
        assertThat(cs.applicationCount()).isEqualTo(2);
        assertThat(cs.latestStatus()).isEqualTo("ACTIVE");      // newest application's status
        assertThat(cs.pan()).isEqualTo("ABCDE1234F");           // staff see the full, unmasked PAN
    }

    // ---- page() / summary() / export(): filtering, sorting and paging live in CustomerBookQuery ----

    @Test
    void pageHydratesOnlyTheIdsSqlReturnedInSqlOrderAndNeverLoadsWholeTables() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        when(bookQuery.count(any())).thenReturn(2L);
        when(bookQuery.pageIds(any(), eq(0), eq(25))).thenReturn(List.of(7L, 3L));
        when(applicationRepository.findByCustomerIdIn(List.of(7L, 3L))).thenReturn(List.of(
                app(30, 3L, ApplicationStatus.ACTIVE), app(70, 7L, ApplicationStatus.REJECTED)));
        when(profileRepository.findByApplicationIdIn(any())).thenReturn(List.of(
                profile(30, "Three", "AAAPA0003A"), profile(70, "Seven", "AAAPA0007A")));

        var page = service.page(null, null, null, null, false, 1, 25);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(25);
        // SQL order (stage date desc) is preserved even though hydration groups by customer id.
        assertThat(page.rows()).extracting(CustomerSummary::customerId).containsExactly(7L, 3L);
        assertThat(page.rows().get(0).name()).isEqualTo("Seven");
        verify(applicationRepository, org.mockito.Mockito.never()).findAll();
        verify(loanRepository, org.mockito.Mockito.never()).findAll();
        verify(ownerRepository, org.mockito.Mockito.never()).findAll();
    }

    @Test
    void pageCapsSizeAndFloorsPageNumber() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        when(bookQuery.pageIds(any(), eq(0), eq(CustomerService.MAX_PAGE_SIZE))).thenReturn(List.of());

        var page = service.page("raj", null, null, "active", false, 0, 500);

        assertThat(page.size()).isEqualTo(CustomerService.MAX_PAGE_SIZE);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.rows()).isEmpty();
        var captor = org.mockito.ArgumentCaptor.forClass(CustomerBookQuery.BookFilter.class);
        verify(bookQuery).pageIds(captor.capture(), eq(0), eq(CustomerService.MAX_PAGE_SIZE));
        assertThat(captor.getValue().needle()).isEqualTo("raj");
        assertThat(captor.getValue().segment()).isEqualTo("active");
        assertThat(captor.getValue().scopeIds()).isNull();      // ADMIN sees the whole book
        assertThat(captor.getValue().mineIds()).isNull();
    }

    @Test
    void pageScopesAnExecutiveToTheirOwnBookInSql() {
        ActorContext.set(new CurrentActor("9", "Exec", "CREDIT_EXECUTIVE"));
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(9L)).thenReturn(java.util.Set.of(7L));
        when(bookQuery.pageIds(any(), eq(0), eq(25))).thenReturn(List.of());

        service.page(null, null, null, null, false, 1, 25);

        var captor = org.mockito.ArgumentCaptor.forClass(CustomerBookQuery.BookFilter.class);
        verify(bookQuery).count(captor.capture());
        assertThat(captor.getValue().scopeIds()).containsExactly(7L);
        assertThat(captor.getValue().scopeIncludesUnallocated()).isFalse();
    }

    @Test
    void mineMeansOwnedByOrDecidedByTheCaller() {
        ActorContext.set(new CurrentActor("5", "Head", "CREDIT_HEAD"));
        when(ownerRepository.findCustomerIdsByOwnerStaffId(5L)).thenReturn(java.util.Set.of(1L));
        ApplicationEvent sanction = new ApplicationEvent();
        sanction.setApplicationId(42L);
        sanction.setAction("SANCTION");
        when(applicationEventRepository.findByActorIdOrderByAtDesc("5")).thenReturn(List.of(sanction));
        when(applicationRepository.findCustomerIdsByIdIn(List.of(42L))).thenReturn(java.util.Set.of(2L));
        when(bookQuery.pageIds(any(), eq(0), eq(25))).thenReturn(List.of());

        service.page(null, null, null, null, true, 1, 25);

        var captor = org.mockito.ArgumentCaptor.forClass(CustomerBookQuery.BookFilter.class);
        verify(bookQuery).count(captor.capture());
        assertThat(captor.getValue().mineIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void summaryMapsSegmentsAndOverlaysUnallocated() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        when(bookQuery.segmentCounts(any())).thenReturn(List.of(
                new CustomerBookQuery.SegmentCount("active", 3, 1),
                new CustomerBookQuery.SegmentCount("rejected", 2, 2)));

        var counts = service.summary(null, null, null, false);

        assertThat(counts.all()).isEqualTo(5);
        assertThat(counts.active()).isEqualTo(3);
        assertThat(counts.rejected()).isEqualTo(2);
        assertThat(counts.unallocated()).isEqualTo(3);
        assertThat(counts.pending()).isZero();
        assertThat(counts.overdue()).isZero();
    }

    @Test
    void exportIsAdminOnlyAndPageRejectsDsa() {
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        assertThatThrownBy(() -> service.export(null, null, null, null, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ADMIN");

        ActorContext.set(new CurrentActor("77", "Agent", "DSA"));
        assertThatThrownBy(() -> service.page(null, null, null, null, false, 1, 25))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DSA");
        assertThatThrownBy(() -> service.summary(null, null, null, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DSA");
    }

    /** Minimal implementer of the interface projection {@code findCurrentStatusEnteredAt} returns. */
    private static com.navix.loan.repository.ApplicationEventRepository.StatusEnteredAt statusEnteredAt(
            long applicationId, java.time.Instant at) {
        return new com.navix.loan.repository.ApplicationEventRepository.StatusEnteredAt() {
            @Override
            public Long getApplicationId() {
                return applicationId;
            }

            @Override
            public java.time.Instant getAt() {
                return at;
            }
        };
    }

    @Test
    void hydratedRowUsesTheCurrentStatusEnteredAtProjectionForStatusChangedAt() {
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        LoanApplication a = app(1, 9000001L, ApplicationStatus.REJECTED);
        java.time.Instant rejectedAt = java.time.Instant.parse("2026-08-01T10:00:00Z");
        when(applicationRepository.findByCustomerIdIn(List.of(9000001L))).thenReturn(List.of(a));
        when(profileRepository.findByApplicationIdIn(any())).thenReturn(List.of());
        // The map iterated to build the query's argument is a HashMap-backed Collection, not a
        // List, so match structurally rather than on List.of(1L) (List.equals rejects non-Lists).
        when(applicationEventRepository.findCurrentStatusEnteredAt(any()))
                .thenReturn(List.of(statusEnteredAt(1L, rejectedAt)));

        CustomerSummary cs = service.byIds(List.of(9000001L)).get(0);

        // The rejection date, NOT a later reassignment/mark-pending — that is the whole point of
        // querying findCurrentStatusEnteredAt rather than "the latest event" for the application.
        assertThat(cs.statusChangedAt()).isEqualTo(rejectedAt);
    }

    @Test
    void hydratedRowFallsBackToCreatedAtWhenNoTransitionEventExists() {
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        LoanApplication a = app(1, 9000001L, ApplicationStatus.DRAFT);
        java.time.Instant createdAt = java.time.Instant.parse("2026-07-01T09:00:00Z");
        a.setCreatedAt(createdAt);
        when(applicationRepository.findByCustomerIdIn(List.of(9000001L))).thenReturn(List.of(a));
        when(profileRepository.findByApplicationIdIn(any())).thenReturn(List.of());
        // Unstubbed findCurrentStatusEnteredAt returns Mockito's default empty list — the intended
        // fallback path, no stub needed — but stub it explicitly here to make the case unambiguous.
        when(applicationEventRepository.findCurrentStatusEnteredAt(any())).thenReturn(List.of());

        CustomerSummary cs = service.byIds(List.of(9000001L)).get(0);

        assertThat(cs.statusChangedAt()).isEqualTo(createdAt);
    }

    @Test
    void updateProfileRejectedForNonAdmin() {
        ActorContext.set(new CurrentActor("7", "Acc", "ACCOUNTANT"));
        assertThatThrownBy(() -> service.updateProfile(9000001L,
                new UpdateCustomerRequest(null, "New Name", null, null, null, null, null, null, null, null)))
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
                new UpdateCustomerRequest(null, "Asha R. Rao", "12 MG Road", "Globex", "SALARIED", 6_000_000L, null, null, null, "HDFC"));

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

        service.updateProfile(9000001L,
                new UpdateCustomerRequest(null, "Asha", null, null, null, 6_000_000L, null, null, null, null));

        verify(changeLogRepository, atLeastOnce()).save(any());   // the salary change is recorded
        // Recomputing is EligibilityService's job (it honours an ADMIN limit override, V69); this
        // asserts the delegation, and EligibilityServiceTest covers what the recompute actually writes.
        verify(eligibilityService).recomputeForCustomer(9000001L, 6_000_000L);
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

    /** Mirrors CustomerReviewService's DUPLICATE_MOBILE rule — a number cannot land on two customers. */
    @Test
    void requestMobileChangeOtpRejectsANumberAlreadyOnAnotherCustomer() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(app(2, 9000001L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(profile(2, "Asha", "ABCDE1234F")));
        when(profileRepository.existsMobileForOtherCustomer("9876543210", 9000001L)).thenReturn(true);

        assertThatThrownBy(() -> service.requestMobileChangeOtp(9000001L, "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already registered with another customer");
        verify(otpVerifier, org.mockito.Mockito.never()).request(anyString(), anyString());
    }

    @Test
    void confirmMobileChangeRejectsANumberAlreadyOnAnotherCustomerEvenWithAValidOtp() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        CustomerProfile p = profile(2, "Asha", "ABCDE1234F");
        p.setMobile("9000000000");
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(app(2, 9000001L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(p));
        when(profileRepository.existsMobileForOtherCustomer("9876543210", 9000001L)).thenReturn(true);
        lenient().when(otpVerifier.verify(eq("9876543210"), anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.confirmMobileChange(9000001L, "9876543210", "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already registered with another customer");
        assertThat(p.getMobile()).isEqualTo("9000000000"); // untouched
        verify(profileRepository, org.mockito.Mockito.never()).save(any());
    }

    /**
     * Login identity keeps only the last 7 digits, so two DIFFERENT 10-digit numbers can derive the
     * same identity even though they are never string-equal — the plain DUPLICATE_MOBILE check above
     * cannot see this; only BorrowerIdentityPort's cross-module lookup against the real claim table
     * can. This is the follow-up closing the gap the independent review flagged in commit 2765cb1.
     */
    @Test
    void requestMobileChangeOtpRejectsANumberThatCollidesWithAnotherCustomersLoginIdentity() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(app(2, 9000001L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(profile(2, "Asha", "ABCDE1234F")));
        when(profileRepository.existsMobileForOtherCustomer("9876543210", 9000001L)).thenReturn(false); // not a literal duplicate
        when(borrowerIdentity.wouldCollideWithAnotherCustomer("9876543210", 9000001L)).thenReturn(true);

        assertThatThrownBy(() -> service.requestMobileChangeOtp(9000001L, "9876543210"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("login identity is already claimed");
        verify(otpVerifier, org.mockito.Mockito.never()).request(anyString(), anyString());
    }

    @Test
    void confirmMobileChangeRejectsAnIdentityCollisionEvenWithAValidOtp() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        CustomerProfile p = profile(2, "Asha", "ABCDE1234F");
        p.setMobile("9000000000");
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(app(2, 9000001L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(p));
        when(borrowerIdentity.wouldCollideWithAnotherCustomer("9876543210", 9000001L)).thenReturn(true);
        lenient().when(otpVerifier.verify(eq("9876543210"), anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.confirmMobileChange(9000001L, "9876543210", "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("login identity is already claimed");
        assertThat(p.getMobile()).isEqualTo("9000000000"); // untouched
        verify(profileRepository, org.mockito.Mockito.never()).save(any());
    }

    /** A number that collides with THIS SAME customer's own claimed identity is not an error. */
    @Test
    void requestMobileChangeOtpAllowsCorrectingToTheCustomersOwnClaimedIdentity() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(app(2, 9000001L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(profile(2, "Asha", "ABCDE1234F")));
        when(borrowerIdentity.wouldCollideWithAnotherCustomer("9876543210", 9000001L)).thenReturn(false);
        var expected = new com.navix.common.verification.OtpVerifierPort.OtpRequestResult(true, null, 300);
        when(otpVerifier.request("9876543210", com.navix.common.verification.OtpVerifierPort.ADMIN_MOBILE_CHANGE))
                .thenReturn(expected);

        assertThat(service.requestMobileChangeOtp(9000001L, "9876543210").sent()).isTrue();
    }

    // ---------------------------------------------------------------- sanctioned-amount correction

    @Test
    void changeSanctionedAmountRejectedForNonAdmin() {
        ActorContext.set(new CurrentActor("7", "Acc", "ACCOUNTANT"));
        assertThatThrownBy(() -> service.changeSanctionedAmount(9000001L, 2L, 500_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void changeSanctionedAmountRejectsWhenTheApplicationIsAlreadyDisbursed() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        // Sanctioned but already disbursed — must NOT be a correction target.
        LoanApplication disbursed = app(2, 9000001L, ApplicationStatus.ACTIVE);
        disbursed.setSanctionedAmountPaise(500_000L);
        disbursed.setLoanId(77L);
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(disbursed));

        assertThatThrownBy(() -> service.changeSanctionedAmount(9000001L, 2L, 600_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not sanctioned");
    }

    /**
     * A CANCELLED/REJECTED application can retain a stale sanctionedAmountPaise from before it was
     * abandoned; loanId stays null since it never disbursed. Without an explicit status check this
     * would incorrectly qualify as a live correction target.
     */
    @Test
    void changeSanctionedAmountRejectsACancelledApplicationEvenThoughItsLoanIdIsNull() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        LoanApplication cancelled = app(2, 9000001L, ApplicationStatus.CANCELLED);
        cancelled.setSanctionedAmountPaise(500_000L);
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(cancelled));

        assertThatThrownBy(() -> service.changeSanctionedAmount(9000001L, 2L, 600_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not sanctioned");
    }

    @Test
    void changeSanctionedAmountRejectsAnApplicationIdThatIsNotThisCustomersOwn() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        LoanApplication a = app(2, 9000001L, ApplicationStatus.SANCTIONED);
        a.setSanctionedAmountPaise(500_000L);
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(a));

        // applicationId 99 was never returned for this customer — must not silently fall back to app 2.
        assertThatThrownBy(() -> service.changeSanctionedAmount(9000001L, 99L, 600_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not sanctioned");
    }

    @Test
    void changeSanctionedAmountRejectsBelowTheMinimum() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        LoanApplication a = app(2, 9000001L, ApplicationStatus.SANCTIONED);
        a.setSanctionedAmountPaise(500_000L);
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(a));

        assertThatThrownBy(() -> service.changeSanctionedAmount(9000001L, 2L, 50_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("below the minimum");
    }

    @Test
    void changeSanctionedAmountRejectsBelowWhatTheBorrowerAlreadyChoseToDraw() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        LoanApplication a = app(2, 9000001L, ApplicationStatus.SANCTIONED);
        a.setSanctionedAmountPaise(500_000L);
        a.setAmountRequested(400_000L);
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(a));

        assertThatThrownBy(() -> service.changeSanctionedAmount(9000001L, 2L, 300_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already chose to draw");
    }

    @Test
    void changeSanctionedAmountUpdatesTheAmountWithoutTouchingStatus() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        LoanApplication a = app(2, 9000001L, ApplicationStatus.SANCTIONED);
        a.setSanctionedAmountPaise(500_000L);
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(a));
        when(applicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.changeSanctionedAmount(9000001L, 2L, 700_000L);

        assertThat(a.getSanctionedAmountPaise()).isEqualTo(700_000L);
        assertThat(a.getStatus()).isEqualTo(ApplicationStatus.SANCTIONED); // status untouched
        verify(changeLogRepository).save(any());
        verify(eventPublisher).publishEvent(
                any(com.navix.common.notification.event.SanctionedAmountRevisedEvent.class));
    }

    // ---- eligible-limit override (V69) ---------------------------------------------

    @Test
    void setLimitOverrideStoresTheLimitAndNotifiesTheBorrowerOfAnIncrease() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        when(limitOverrideRepository.findById(9000001L)).thenReturn(Optional.empty());
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of());

        service.setLimitOverride(9000001L, 2_000_000L, "top performer");

        verify(limitOverrideRepository).save(any());
        verify(changeLogRepository).save(any());   // audited like every admin correction
        verify(eventPublisher).publishEvent(
                any(com.navix.common.notification.event.LoanLimitRevisedEvent.class));
    }

    /** Clearing the override is not something to push at a borrower. */
    @Test
    void clearingTheLimitOverrideDeletesItAndNotifiesNobody() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        com.navix.loan.entity.CustomerLimitOverride ov = new com.navix.loan.entity.CustomerLimitOverride();
        ov.setCustomerId(9000001L);
        ov.setLimitPaise(2_000_000L);
        when(limitOverrideRepository.findById(9000001L)).thenReturn(Optional.of(ov));
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of());

        service.setLimitOverride(9000001L, null, null);

        verify(limitOverrideRepository).deleteById(9000001L);
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(
                any(com.navix.common.notification.event.LoanLimitRevisedEvent.class));
    }

    @Test
    void setLimitOverrideRejectedBelowTheMinimumLoan() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));

        assertThatThrownBy(() -> service.setLimitOverride(9000001L, 50_000L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("minimum");
    }

    @Test
    void setLimitOverrideRejectedForNonAdmin() {
        ActorContext.set(new CurrentActor("7", "Acc", "ACCOUNTANT"));

        assertThatThrownBy(() -> service.setLimitOverride(9000001L, 2_000_000L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ADMIN");
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
        // hydrate() only ever asks for the ids that survived the scope filter, so answer per id
        // rather than returning the whole table — that IS the behaviour under test.
        lenient().when(applicationRepository.findByCustomerIdIn(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return java.util.stream.Stream.of(
                            app(1, 9000001L, ApplicationStatus.ACTIVE),
                            app(2, 9000002L, ApplicationStatus.ACTIVE))
                    .filter(a -> ids.contains(a.getCustomerId()))
                    .toList();
        });
        // Some scoped tests filter every customer out before the batched profile query would run.
        lenient().when(profileRepository.findByApplicationIdIn(any())).thenReturn(List.of(
                profile(1, "Asha Rao", "AAAAA1111A"), profile(2, "Bhavya Reddy", "BBBBB2222B")));
    }

    /** Ask for both customers and see which ones the caller's scope lets through. */
    private List<CustomerSummary> visibleOfTheTwo() {
        return service.byIds(List.of(9000001L, 9000002L));
    }

    @Test
    void byIds_asCreditExecutive_showsOnlyAssignedAndDecidedCustomers() {
        ActorContext.set(new CurrentActor("12", "Exec", "CREDIT_EXECUTIVE"));
        givenTwoCustomers();
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(12L))
                .thenReturn(java.util.Set.of(9000001L));
        when(applicationEventRepository.findByActorIdOrderByAtDesc("12")).thenReturn(List.of());

        assertThat(visibleOfTheTwo()).extracting(CustomerSummary::customerId)
                .containsExactly(9000001L);
    }

    @Test
    void byIds_asCreditExecutive_includesCustomersTheyDecidedOn() {
        ActorContext.set(new CurrentActor("12", "Exec", "CREDIT_EXECUTIVE"));
        givenTwoCustomers();
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(12L)).thenReturn(java.util.Set.of());
        when(applicationEventRepository.findByActorIdOrderByAtDesc("12"))
                .thenReturn(List.of(decision(2L, "12", "SANCTION")));
        when(applicationRepository.findCustomerIdsByIdIn(List.of(2L))).thenReturn(java.util.Set.of(9000002L));

        assertThat(visibleOfTheTwo()).extracting(CustomerSummary::customerId)
                .containsExactly(9000002L);
    }

    @Test
    void byIds_ignoresNonDecisionEventsWhenScoping() {
        ActorContext.set(new CurrentActor("12", "Exec", "CREDIT_EXECUTIVE"));
        givenTwoCustomers();
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(12L)).thenReturn(java.util.Set.of());
        // Merely creating/submitting an application is routing noise, not a decision.
        when(applicationEventRepository.findByActorIdOrderByAtDesc("12"))
                .thenReturn(List.of(decision(2L, "12", "CREATE")));

        assertThat(visibleOfTheTwo()).isEmpty();
    }

    /**
     * The bug this guards: a collections officer acquires neither of the credit-side scope sources —
     * {@code assigned_executive_id} is the credit assignment, and every DECISION_ACTION is a
     * credit/disbursement action — so before the case seam was consulted they could see none of the
     * borrowers they were chasing, and remarks/call logs/customer detail all 404'd on their own case.
     */
    @Test
    void byIds_asCollectionExecutive_showsCustomersOnCasesAssignedToThem() {
        ActorContext.set(new CurrentActor("77", "Collections Exec", "COLLECTION_EXECUTIVE"));
        givenTwoCustomers();
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(77L))
                .thenReturn(java.util.Set.of());
        when(applicationEventRepository.findByActorIdOrderByAtDesc("77")).thenReturn(List.of());
        when(collectionCaseDirectory.loanIdsAssignedTo(77L)).thenReturn(java.util.Set.of(500L));
        when(loanRepository.findCustomerIdsByIdIn(java.util.Set.of(500L)))
                .thenReturn(java.util.Set.of(9000002L));

        assertThat(visibleOfTheTwo()).extracting(CustomerSummary::customerId)
                .containsExactly(9000002L);
    }

    @Test
    void byIds_asCollectionExecutive_stillHidesCustomersWithNoCaseOfTheirs() {
        ActorContext.set(new CurrentActor("77", "Collections Exec", "COLLECTION_EXECUTIVE"));
        givenTwoCustomers();
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(77L))
                .thenReturn(java.util.Set.of());
        when(applicationEventRepository.findByActorIdOrderByAtDesc("77")).thenReturn(List.of());
        // Holding no cases must still mean seeing nobody: the seam widens the scope to their OWN
        // worklist, not to every borrower who happens to be in collections.
        when(collectionCaseDirectory.loanIdsAssignedTo(77L)).thenReturn(java.util.Set.of());

        assertThat(visibleOfTheTwo()).isEmpty();
    }

    @Test
    void byIds_asCreditHead_showsEveryCustomer() {
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        givenTwoCustomers();

        assertThat(visibleOfTheTwo()).extracting(CustomerSummary::customerId)
                .containsExactlyInAnyOrder(9000001L, 9000002L);
    }

    @Test
    void byIds_asTelecaller_alsoSeesUnallocatedCustomers() {
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

        assertThat(visibleOfTheTwo()).extracting(CustomerSummary::customerId)
                .containsExactly(9000001L);
    }

    @Test
    void byIds_asTelecaller_keepsCustomersTheyHaveClaimed() {
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

        assertThat(visibleOfTheTwo()).extracting(CustomerSummary::customerId)
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

    /**
     * The window is resolved in IST server-side, not in the browser and not in UTC: an application
     * created at 23:30 IST on the 19th is still 18:00 UTC that day, so a UTC-resolved window would
     * push it into the 20th and drop it from a "Today = 19th" filter. SQL does the filtering now,
     * so what has to hold is the pair of instants handed to it.
     */
    @Test
    void pageResolvesTheDateWindowInIst() {
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        when(bookQuery.pageIds(any(), eq(0), eq(25))).thenReturn(List.of());

        java.time.LocalDate d19 = java.time.LocalDate.of(2026, 8, 19);
        service.page(null, d19, d19, null, false, 1, 25);

        var captor = org.mockito.ArgumentCaptor.forClass(CustomerBookQuery.BookFilter.class);
        verify(bookQuery).count(captor.capture());
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        assertThat(captor.getValue().from()).isEqualTo(d19.atStartOfDay(ist).toInstant());
        // Exclusive upper bound: midnight IST at the START of the 20th, so 23:30 IST on the 19th
        // is inside the window and 00:00 IST on the 20th is not.
        assertThat(captor.getValue().to()).isEqualTo(d19.plusDays(1).atStartOfDay(ist).toInstant());
        assertThat(captor.getValue().today()).isEqualTo(java.time.LocalDate.now(ist));
    }

    @Test
    void byIdsPricesEveryLoanInOneBatchedPass() {
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        when(applicationRepository.findByCustomerIdIn(any())).thenReturn(List.of(
                app(1, 9000001L, ApplicationStatus.ACTIVE),
                app(2, 9000002L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationIdIn(any())).thenReturn(List.of(
                profile(1, "Asha Rao", "AAAAA1111A"), profile(2, "Bhavya Reddy", "BBBBB2222B")));
        com.navix.loan.entity.Loan l1 = loan(500L, 9000001L);
        com.navix.loan.entity.Loan l2 = loan(501L, 9000001L);
        com.navix.loan.entity.Loan l3 = loan(502L, 9000002L);
        l1.setStatus(com.navix.loan.domain.LoanStatus.ACTIVE);
        l2.setStatus(com.navix.loan.domain.LoanStatus.ACTIVE);
        l3.setStatus(com.navix.loan.domain.LoanStatus.ACTIVE);
        when(loanRepository.findByCustomerIdIn(any())).thenReturn(List.of(l1, l2, l3));
        when(repaymentService.outstandingForAll(any(), any())).thenReturn(java.util.Map.of(
                500L, 10_000L, 501L, 5_000L, 502L, 20_000L));

        List<CustomerSummary> rows = service.byIds(List.of(9000001L, 9000002L));

        assertThat(rows).hasSize(2);
        CustomerSummary c1 = rows.stream().filter(r -> r.customerId() == 9000001L).findFirst().orElseThrow();
        CustomerSummary c2 = rows.stream().filter(r -> r.customerId() == 9000002L).findFirst().orElseThrow();
        assertThat(c1.totalOutstandingPaise()).isEqualTo(15_000L);
        assertThat(c2.totalOutstandingPaise()).isEqualTo(20_000L);
        verify(repaymentService, org.mockito.Mockito.never()).outstandingAsOf(any(), any());
        verify(loanRepository, org.mockito.Mockito.never()).findByCustomerId(any());
        verify(loanRepository, org.mockito.Mockito.never()).findAll();
        verify(loanRepository, org.mockito.Mockito.times(1)).findByCustomerIdIn(any());
    }

    @Test
    void byIdsResolvesProfilesInOneQuery() {
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        when(applicationRepository.findByCustomerIdIn(any())).thenReturn(List.of(
                app(1, 9000001L, ApplicationStatus.CLOSED),
                app(2, 9000001L, ApplicationStatus.ACTIVE)));
        when(profileRepository.findByApplicationIdIn(any())).thenReturn(List.of(
                profile(1, "Old Name", "ABCDE1234F"), profile(2, "Asha Rao", "ABCDE1234F")));

        service.byIds(List.of(9000001L));

        verify(profileRepository, org.mockito.Mockito.times(1)).findByApplicationIdIn(any());
        verify(profileRepository, org.mockito.Mockito.never()).findByApplicationId(any());
    }

    // --- byIds(): the batched twin of one summary row ------------------------------------------

    @Test
    void byIdsHydratesOnlyPermittedIdsInInputOrder() {
        // A scoped executive asking for two customers gets back only the one their scope permits —
        // silently, with no 403/404 that would confirm the other customer exists.
        ActorContext.set(new CurrentActor("12", "Exec", "CREDIT_EXECUTIVE"));
        givenTwoCustomers();
        when(applicationRepository.findCustomerIdsByAssignedExecutiveId(12L))
                .thenReturn(java.util.Set.of(9000001L));
        when(applicationEventRepository.findByActorIdOrderByAtDesc("12")).thenReturn(List.of());

        assertThat(service.byIds(List.of(9000002L, 9000001L)))
                .extracting(CustomerSummary::customerId)
                .containsExactly(9000001L);

        // …and for a caller who may see both, the rows come back in the order they were ASKED for,
        // not in whatever order grouping by customer id happens to produce.
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        assertThat(service.byIds(List.of(9000002L, 9000001L)))
                .extracting(CustomerSummary::customerId)
                .containsExactly(9000002L, 9000001L);
    }

    @Test
    void byIdsRejectsDsa() {
        ActorContext.set(new CurrentActor("77", "Agent", "DSA"));
        assertThatThrownBy(() -> service.byIds(List.of(9000001L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DSA");
        verify(applicationRepository, org.mockito.Mockito.never()).findByCustomerIdIn(any());
    }

    @Test
    void byIdsClampsAtMaxPageSize() {
        // A batch lookup, not a second whole-book list endpoint: 150 ids in, at most 100 queried.
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 150).boxed().toList();
        when(applicationRepository.findByCustomerIdIn(any())).thenReturn(List.of());

        service.byIds(ids);

        // The FIRST 100 of the 150, in the order asked for — not a sample and not all 150.
        verify(applicationRepository).findByCustomerIdIn(org.mockito.ArgumentMatchers.argThat(
                c -> c.size() == CustomerService.MAX_PAGE_SIZE && c.contains(1L) && c.contains(100L)
                        && !c.contains(101L)));
    }

    @Test
    void byIdsEmptyReturnsEmptyWithoutQueries() {
        ActorContext.set(new CurrentActor("12", "Exec", "CREDIT_EXECUTIVE"));

        assertThat(service.byIds(List.of())).isEmpty();
        assertThat(service.byIds(null)).isEmpty();

        // Not even the scope resolution runs — there is nothing to scope.
        verify(applicationRepository, org.mockito.Mockito.never()).findByCustomerIdIn(any());
        verify(applicationRepository, org.mockito.Mockito.never())
                .findCustomerIdsByAssignedExecutiveId(any());
    }

    // --- bookStats(): the dashboard roll-up, server-side ---------------------------------------
    // The numeric cases below mirror frontend/src/lib/staff/my-stats.test.ts so both sides of the
    // port stay pinned to the same numbers.

    /** {@code mine} = owned by the caller; hydration answers with the given apps/loans. */
    private void givenMyBook(java.util.Set<Long> mineIds, List<LoanApplication> apps,
            List<com.navix.loan.entity.Loan> loans) {
        when(ownerRepository.findCustomerIdsByOwnerStaffId(5L)).thenReturn(mineIds);
        when(applicationEventRepository.findByActorIdOrderByAtDesc("5")).thenReturn(List.of());
        lenient().when(applicationRepository.findByCustomerIdIn(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return apps.stream().filter(a -> ids.contains(a.getCustomerId())).toList();
        });
        lenient().when(loanRepository.findByCustomerIdIn(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return loans.stream().filter(l -> ids.contains(l.getCustomerId())).toList();
        });
        lenient().when(profileRepository.findByApplicationIdIn(any())).thenReturn(List.of());
    }

    /** An ACTIVE loan for {@code customerId} falling due {@code daysAgo} days before today (IST). */
    private com.navix.loan.entity.Loan dueLoan(long id, long customerId, int daysAgo) {
        com.navix.loan.entity.Loan l = loan(id, customerId);
        l.setStatus(com.navix.loan.domain.LoanStatus.ACTIVE);
        l.setDueDate(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(daysAgo));
        return l;
    }

    @Test
    void bookStatsEmptyBookGivesNullRatesAndZeroCounts() {
        // An unmeasurable metric is null, never 0 — a 0 reads as a real, bad number.
        ActorContext.set(new CurrentActor("5", "Head", "CREDIT_HEAD"));
        when(ownerRepository.findCustomerIdsByOwnerStaffId(5L)).thenReturn(java.util.Set.of());
        when(applicationEventRepository.findByActorIdOrderByAtDesc("5")).thenReturn(List.of());

        var stats = service.bookStats();

        assertThat(stats.total()).isZero();
        assertThat(stats.concentrationPct()).isNull();
        assertThat(stats.avgTicketPaise()).isNull();
        assertThat(stats.avgCreditScore()).isNull();
        assertThat(stats.outstandingPaise()).isZero();
        assertThat(stats.largestExposurePaise()).isZero();
        assertThat(stats.counts().all()).isZero();
        assertThat(stats.dpd()).isEqualTo(new com.navix.loan.dto.CustomerDtos.DpdBuckets(0, 0, 0));
        assertThat(stats.dueNext7Days()).isZero();
        verify(applicationRepository, org.mockito.Mockito.never()).findByCustomerIdIn(any());
    }

    @Test
    void bookStatsDpdBoundaries30_31_60_61() {
        // Exactly 30 -> d1to30, 31 -> d31to60, 60 -> d31to60, 61 -> d60plus. Same boundaries the
        // frontend's bookStats test pins.
        ActorContext.set(new CurrentActor("5", "Head", "CREDIT_HEAD"));
        givenMyBook(java.util.Set.of(1L, 2L, 3L, 4L),
                List.of(app(1, 1L, ApplicationStatus.ACTIVE), app(2, 2L, ApplicationStatus.ACTIVE),
                        app(3, 3L, ApplicationStatus.ACTIVE), app(4, 4L, ApplicationStatus.ACTIVE)),
                List.of(dueLoan(101, 1L, 30), dueLoan(102, 2L, 31),
                        dueLoan(103, 3L, 60), dueLoan(104, 4L, 61)));

        var stats = service.bookStats();

        assertThat(stats.total()).isEqualTo(4);
        assertThat(stats.dpd().d1to30()).isEqualTo(1);
        assertThat(stats.dpd().d31to60()).isEqualTo(2);
        assertThat(stats.dpd().d60plus()).isEqualTo(1);
        // Every one of them is past due, so none is "due in the next 7 days".
        assertThat(stats.dueNext7Days()).isZero();
        // effectiveStatus turns a past-due ACTIVE loan into OVERDUE, which is what segments read.
        assertThat(stats.counts().overdue()).isEqualTo(4);
    }

    @Test
    void bookStatsCountsALoanFallingDueInsideTheNextWeek() {
        ActorContext.set(new CurrentActor("5", "Head", "CREDIT_HEAD"));
        givenMyBook(java.util.Set.of(1L, 2L),
                List.of(app(1, 1L, ApplicationStatus.ACTIVE), app(2, 2L, ApplicationStatus.ACTIVE)),
                List.of(dueLoan(101, 1L, -3), dueLoan(102, 2L, -8)));

        var stats = service.bookStats();

        assertThat(stats.dueNext7Days()).isEqualTo(1);   // the 8-days-out loan is outside the window
        assertThat(stats.dpd().d1to30()).isZero();
    }

    @Test
    void bookStatsConcentrationNullWhenOutstandingIsZero() {
        ActorContext.set(new CurrentActor("5", "Head", "CREDIT_HEAD"));
        givenMyBook(java.util.Set.of(1L), List.of(app(1, 1L, ApplicationStatus.ACTIVE)),
                List.of(dueLoan(101, 1L, 1)));
        // Nothing owed on it: outstandingForAll answers 0 for the loan.
        when(repaymentService.outstandingForAll(any(), any()))
                .thenReturn(java.util.Map.of(101L, 0L));

        var stats = service.bookStats();

        assertThat(stats.outstandingPaise()).isZero();
        assertThat(stats.concentrationPct()).isNull();
    }

    @Test
    void bookStatsConcentrationIsLargestOverTotal() {
        ActorContext.set(new CurrentActor("5", "Head", "CREDIT_HEAD"));
        givenMyBook(java.util.Set.of(1L, 2L),
                List.of(app(1, 1L, ApplicationStatus.ACTIVE), app(2, 2L, ApplicationStatus.ACTIVE)),
                List.of(dueLoan(101, 1L, 2), dueLoan(102, 2L, 2)));
        when(repaymentService.outstandingForAll(any(), any()))
                .thenReturn(java.util.Map.of(101L, 3_000L, 102L, 1_000L));

        var stats = service.bookStats();

        assertThat(stats.outstandingPaise()).isEqualTo(4_000L);
        assertThat(stats.largestExposurePaise()).isEqualTo(3_000L);
        assertThat(stats.concentrationPct()).isEqualTo(3_000d / 4_000d);
        // Both loans are past due, so the whole book is at risk.
        assertThat(stats.atRiskPaise()).isEqualTo(4_000L);
    }

    @Test
    void bookStatsCountsOnlyMyCustomersAndChunksHydration() {
        // 150 customers is more than one IN (...) list should carry, so hydration is chunked at
        // MAX_PAGE_SIZE rather than handed the whole book in one query.
        ActorContext.set(new CurrentActor("5", "Head", "CREDIT_HEAD"));
        java.util.Set<Long> mine = new java.util.LinkedHashSet<>();
        List<LoanApplication> apps = new java.util.ArrayList<>();
        for (long id = 1; id <= 150; id++) {
            mine.add(id);
            apps.add(app(id, id, ApplicationStatus.DRAFT));
        }
        givenMyBook(mine, apps, List.of());

        var stats = service.bookStats();

        assertThat(stats.total()).isEqualTo(150);
        assertThat(stats.toChase()).isEqualTo(150);           // every one is an abandoned DRAFT
        assertThat(stats.counts().incomplete()).isEqualTo(150);
        assertThat(stats.counts().unallocated()).isEqualTo(150); // no customer_owner rows stubbed
        verify(applicationRepository, org.mockito.Mockito.atLeast(2)).findByCustomerIdIn(any());
    }

    @Test
    void bookStatsRejectsDsa() {
        ActorContext.set(new CurrentActor("77", "Agent", "DSA"));
        assertThatThrownBy(() -> service.bookStats())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DSA");
    }

    // --- detail(): one batched pricing pass + the itemised breakdown ---------------------------

    @Test
    void detailPricesLoansInOneBatchedPassAndExposesTheBreakdown() {
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        com.navix.loan.entity.Loan l1 = dueLoan(500L, 9000001L, 2);
        com.navix.loan.entity.Loan l2 = dueLoan(501L, 9000001L, 40);
        l1.setPrincipal(1_000_000L);
        l2.setPrincipal(2_000_000L);
        when(applicationRepository.findByCustomerId(9000001L))
                .thenReturn(List.of(app(1, 9000001L, ApplicationStatus.ACTIVE)));
        when(loanRepository.findByCustomerId(9000001L)).thenReturn(List.of(l1, l2));
        when(profileRepository.findByApplicationIdIn(any())).thenReturn(List.of());
        when(repaymentService.outstandingBreakdownsForAll(any(), eq(null))).thenReturn(java.util.Map.of(
                500L, new RepaymentService.OutstandingBreakdown(10_000L, 2_000L, 500L, 0L, null, 2, 1),
                501L, new RepaymentService.OutstandingBreakdown(20_000L, 4_000L, 900L, 0L, null, 40, 30)));

        var detail = service.detail(9000001L);

        // One batched call priced both loans — never the 3-queries-per-loan single-loan path.
        verify(repaymentService, org.mockito.Mockito.never()).outstandingAsOf(any(), any());
        verify(repaymentService, org.mockito.Mockito.times(1)).outstandingBreakdownsForAll(any(), eq(null));
        assertThat(detail.loans()).extracting(com.navix.loan.dto.LoanDtos.LoanView::outstandingPaise)
                .containsExactly(20_000L, 10_000L);          // newest loan first
        assertThat(detail.outstandingByLoanId()).containsOnlyKeys(500L, 501L);
        var b = detail.outstandingByLoanId().get(500L);
        assertThat(b.loanId()).isEqualTo(500L);
        assertThat(b.outstandingPaise()).isEqualTo(10_000L);
        assertThat(b.interestPaise()).isEqualTo(2_000L);
        assertThat(b.penaltyPaise()).isEqualTo(500L);
        assertThat(b.interestDays()).isEqualTo(2);
        assertThat(b.penaltyDays()).isEqualTo(1);
        assertThat(b.asOf()).isEqualTo(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
    }

    // --- Call log loan tagging (WP2) ----------------------------------------------------------

    private com.navix.loan.entity.Loan loan(long id, long customerId) {
        com.navix.loan.entity.Loan l = new com.navix.loan.entity.Loan();
        l.setId(id);
        l.setCustomerId(customerId);
        return l;
    }

    private com.navix.loan.dto.CustomerDtos.AddCallLogRequest callLogRequest(Long loanId) {
        return new com.navix.loan.dto.CustomerDtos.AddCallLogRequest(
                "OUTBOUND", "CONNECTED", null, "Discussed overdue payment", loanId);
    }

    @Test
    void addCallLogWithLoanIdRoundTrips() {
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        when(loanRepository.findById(500L)).thenReturn(Optional.of(loan(500L, 9000001L)));
        when(callLogRepository.save(any())).thenAnswer(inv -> {
            CustomerCallLog saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        var view = service.addCallLog(9000001L, callLogRequest(500L));

        assertThat(view.loanId()).isEqualTo(500L);
        org.mockito.ArgumentCaptor<CustomerCallLog> captor =
                org.mockito.ArgumentCaptor.forClass(CustomerCallLog.class);
        verify(callLogRepository).save(captor.capture());
        assertThat(captor.getValue().getLoanId()).isEqualTo(500L);
        assertThat(captor.getValue().getCustomerId()).isEqualTo(9000001L);
    }

    @Test
    void callLogsFilterByLoanIdReturnsOnlyTaggedRows() {
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        CustomerCallLog tagged = new CustomerCallLog();
        tagged.setId(1L);
        tagged.setCustomerId(9000001L);
        tagged.setLoanId(500L);
        tagged.setCallType("OUTBOUND");
        tagged.setOutcome("CONNECTED");
        when(callLogRepository.findByCustomerIdAndLoanIdOrderByIdDesc(9000001L, 500L))
                .thenReturn(List.of(tagged));

        var rows = service.callLogs(9000001L, 500L);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).loanId()).isEqualTo(500L);
        verify(callLogRepository).findByCustomerIdAndLoanIdOrderByIdDesc(9000001L, 500L);
        verify(callLogRepository, org.mockito.Mockito.never()).findByCustomerIdOrderByIdDesc(any());
    }

    @Test
    void addCallLogRejectsLoanBelongingToAnotherCustomer() {
        ActorContext.set(new CurrentActor("31", "Credit Head", "CREDIT_HEAD"));
        when(loanRepository.findById(500L)).thenReturn(Optional.of(loan(500L, 9000002L)));

        assertThatThrownBy(() -> service.addCallLog(9000001L, callLogRequest(500L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "LOAN_CUSTOMER_MISMATCH");
    }

    private void givenAssignableCustomer(String actorId, String actorName, String actorRole) {
        ActorContext.set(new CurrentActor(actorId, actorName, actorRole));
        LoanApplication application = app(1, 9000001L, ApplicationStatus.ACTIVE);
        CustomerProfile customerProfile = profile(1, "Asha Rao", "ABCDE1234F");
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(application));
        when(profileRepository.findByApplicationId(1L)).thenReturn(Optional.of(customerProfile));
        when(loanRepository.findByCustomerId(9000001L)).thenReturn(List.of());
    }

    // ---------------------------------------------------------------- salary-credit-day correction

    @Test
    void changeSalaryCreditDayRejectedForNonAdmin() {
        ActorContext.set(new CurrentActor("7", "Acc", "ACCOUNTANT"));
        assertThatThrownBy(() -> service.changeSalaryCreditDay(9000001L, 15))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void changeSalaryCreditDayRejectsOutOfRange() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        assertThatThrownBy(() -> service.changeSalaryCreditDay(9000001L, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("INVALID_SALARY_DAY");
        assertThatThrownBy(() -> service.changeSalaryCreditDay(9000001L, 32))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("INVALID_SALARY_DAY");
    }

    @Test
    void changeSalaryCreditDayRejectsCustomerWithoutApplications() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.changeSalaryCreditDay(9000001L, 15))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("NO_APPLICATION");
    }

    @Test
    void changeSalaryCreditDayUpdatesLatestApplicationAndLogsTheChange() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        LoanApplication older = app(5, 9000001L, ApplicationStatus.CLOSED);
        // KYC_APPROVED (not SANCTIONED), so this exercises only the plain-day-update path.
        LoanApplication newer = app(9, 9000001L, ApplicationStatus.KYC_APPROVED);
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(older, newer));
        when(applicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var view = service.changeSalaryCreditDay(9000001L, 15);

        assertThat(view.id()).isEqualTo(9L);               // the higher id is saved, not the older one
        assertThat(newer.getSalaryCreditDay()).isEqualTo(15);
        assertThat(older.getSalaryCreditDay()).isNull();    // the older application is left alone

        org.mockito.ArgumentCaptor<com.navix.loan.entity.ProfileChangeLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.navix.loan.entity.ProfileChangeLog.class);
        verify(changeLogRepository).save(captor.capture());
        assertThat(captor.getValue().getField()).isEqualTo("salaryCreditDay");
        assertThat(captor.getValue().getNewValue()).isEqualTo("15");
    }

    @Test
    void changeSalaryCreditDayRecomputesPendingOfferRepaymentDate() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        LoanApplication a = app(2, 9000001L, ApplicationStatus.SANCTIONED); // loanId null = pending offer
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(a));
        when(applicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        java.time.LocalDate stubbedDue = java.time.LocalDate.of(2026, 9, 20);
        when(loanMath.dueDateFromSalary(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(stubbedDue);

        service.changeSalaryCreditDay(9000001L, 20);

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        assertThat(a.getApprovedRepaymentDate()).isEqualTo(stubbedDue);
        assertThat(a.getSanctionTenureDays())
                .isEqualTo((int) java.time.temporal.ChronoUnit.DAYS.between(today, stubbedDue));
    }

    @Test
    void changeSalaryCreditDayLeavesDisbursedApplicationDatesAlone() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        LoanApplication a = app(2, 9000001L, ApplicationStatus.ACTIVE);
        a.setLoanId(77L);
        a.setApprovedRepaymentDate(java.time.LocalDate.of(2026, 6, 30));
        when(applicationRepository.findByCustomerId(9000001L)).thenReturn(List.of(a));
        when(applicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.changeSalaryCreditDay(9000001L, 12);

        assertThat(a.getSalaryCreditDay()).isEqualTo(12);
        assertThat(a.getApprovedRepaymentDate()).isEqualTo(java.time.LocalDate.of(2026, 6, 30)); // unchanged
        verify(loanMath, org.mockito.Mockito.never())
                .dueDateFromSalary(any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
