package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.dto.ReviewDtos.DocumentRequest;
import com.navix.loan.dto.ReviewDtos.EditProfileRequest;
import com.navix.loan.dto.ReviewDtos.ProfileRequest;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerReviewServiceTest {

    private static final long APP_ID = 4L;
    private static final long CUSTOMER_ID = 7L;
    private static final CurrentActor BORROWER =
            new CurrentActor("9001001", "Asha Verma", "BORROWER");
    private static final CurrentActor CREDIT_EXECUTIVE =
            new CurrentActor("41", "Ravi Nair", "CREDIT_EXECUTIVE");
    private static final CurrentActor CREDIT_HEAD =
            new CurrentActor("42", "Neha Rao", "CREDIT_HEAD");
    private static final CurrentActor ACCOUNTANT =
            new CurrentActor("43", "Iqbal Shah", "ACCOUNTANT");

    @Mock
    private LoanApplicationRepository applicationRepository;
    @Mock
    private CustomerProfileRepository profileRepository;
    @Mock
    private ApplicationDocumentRepository documentRepository;
    @Mock
    private DocumentStoragePort storage;
    @Mock
    private VerificationInvalidationService verificationInvalidation;
    @Mock
    private EligibilityService eligibilityService;
    @Mock
    private com.navix.loan.repository.ProfileChangeLogRepository changeLogRepository;
    @Mock
    private ProfileChangeLogger changeLogger;
    @Mock
    private com.navix.loan.repository.ApplicationVerificationRepository verificationRepository;
    @Mock
    private com.navix.common.security.BorrowerIdentityPort borrowerIdentity;

    private CustomerReviewService service;

    @BeforeEach
    void setUp() {
        service = new CustomerReviewService(applicationRepository, profileRepository, documentRepository,
                storage, verificationInvalidation, eligibilityService, changeLogRepository, changeLogger,
                verificationRepository, borrowerIdentity);
        ActorContext.set(BORROWER);
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private ProfileRequest req(String pan, String mobile) {
        return new ProfileRequest("Asha Verma", pan, mobile,
                null, null, null, null, null, null, null);
    }

    /** A persisted application owned by CUSTOMER_ID — saveProfile now resolves the customer via findById. */
    private LoanApplication application() {
        LoanApplication app = new LoanApplication();
        app.setId(APP_ID);
        app.setCustomerId(CUSTOMER_ID);
        app.setStatus(ApplicationStatus.DRAFT);
        return app;
    }

    @Test
    void editOwnProfileInvalidatesChecksAndRecomputesEligibilityOnSalaryChange() {
        CustomerProfile existing = new CustomerProfile();
        existing.setApplicationId(APP_ID);
        existing.setAddress("Old address");
        existing.setMonthlySalaryPaise(5_000_000L);
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.of(existing));
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Change address + salary; identity fields are not part of EditProfileRequest (locked).
        service.editOwnProfile(APP_ID, new EditProfileRequest(
                "New address", null, null, 6_000_000L, null, null, "Mom", "9990001111", "Mother", null));

        assertThat(existing.getAddress()).isEqualTo("New address");
        assertThat(existing.getMonthlySalaryPaise()).isEqualTo(6_000_000L);
        assertThat(existing.getEmergencyContactName()).isEqualTo("Mom");
        // ADDRESS + SALARY checks reset; eligibility recomputed from the new salary.
        verify(verificationInvalidation).invalidateForFields(eq(APP_ID),
                argThat(s -> s.contains("address") && s.contains("monthlySalaryPaise")));
        verify(eligibilityService).recomputeForCustomer(CUSTOMER_ID, 6_000_000L);
    }

    @Test
    void rejectsDuplicatePan() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));
        when(profileRepository.existsPanForOtherCustomer("ABCDE1234F", CUSTOMER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.saveProfile(APP_ID, req("ABCDE1234F", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PAN");
    }

    @Test
    void rejectsDuplicateMobile() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));
        when(profileRepository.existsPanForOtherCustomer("ABCDE1234F", CUSTOMER_ID)).thenReturn(false);
        when(profileRepository.existsMobileForOtherCustomer("9876543210", CUSTOMER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.saveProfile(APP_ID, req("ABCDE1234F", "98765 43210")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("mobile");
    }

    @Test
    void acceptsUniqueIdentityAndNormalises() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));
        when(profileRepository.existsPanForOtherCustomer("ABCDE1234F", CUSTOMER_ID)).thenReturn(false);
        when(profileRepository.existsMobileForOtherCustomer("9876543210", CUSTOMER_ID)).thenReturn(false);
        when(borrowerIdentity.isLoginMobile("9876543210", CUSTOMER_ID)).thenReturn(true);
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.empty());
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // lower-case PAN, +91-prefixed mobile -> all normalised
        CustomerProfile saved = service.saveProfile(APP_ID, req("abcde1234f", "+91 98765 43210"));

        assertThat(saved.getApplicationId()).isEqualTo(APP_ID);
        assertThat(saved.getPan()).isEqualTo("ABCDE1234F");
        assertThat(saved.getMobile()).isEqualTo("9876543210");
    }

    @Test
    void borrowerCannotSetAMobileOtherThanTheirLoginMobile() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));
        when(borrowerIdentity.isLoginMobile("9000000001", CUSTOMER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.saveProfile(APP_ID, req(null, "9000000001")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("signed in with");
        verify(profileRepository, never()).save(any());
    }

    @Test
    void borrowerCannotChangeNamePanOrDobOnceFileLeftDraft() {
        LoanApplication app = application();
        app.setStatus(ApplicationStatus.KYC_PENDING);
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        CustomerProfile existing = new CustomerProfile();
        existing.setApplicationId(APP_ID);
        existing.setFullName("Asha Verma");
        existing.setPan("ABCDE1234F");
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.of(existing));

        // a different PAN is refused; re-sending the same name + PAN is a no-op, not an error
        assertThatThrownBy(() -> service.saveProfile(APP_ID, req("ZZZZZ9999Z", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("can't be changed");
        service.saveProfile(APP_ID, req("ABCDE1234F", null));
    }

    // ---- F5 / F14: verified fields and salary after the check ------------------------------

    private ProfileRequest slice(String address, String employer, Long salary, String email,
                                 String officialEmail, java.time.LocalDate prevSalaryDate) {
        return new ProfileRequest(null, null, null, null, address, employer, null, salary, null, email,
                officialEmail, null, null, null, prevSalaryDate, null, null, null, null);
    }

    private CustomerProfile stubProfile(ApplicationStatus status) {
        LoanApplication app = application();
        app.setStatus(status);
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(APP_ID);
        p.setAddress("Old address");
        p.setEmployer("Acme");
        p.setMonthlySalaryPaise(5_000_000L);
        p.setOfficialEmail("a@acme.com");
        p.setOfficialEmailOtpVerified(true);
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.of(p));
        lenient().when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        return p;
    }

    @Test
    void changingTheOfficialEmailClearsItsOtpFlag_andSameAddressKeepsIt() {
        CustomerProfile p = stubProfile(ApplicationStatus.DRAFT);

        service.saveProfile(APP_ID, slice(null, null, null, null, "A@ACME.com", null));
        assertThat(p.getOfficialEmailOtpVerified()).isTrue();

        service.saveProfile(APP_ID, slice(null, null, null, null, "b@acme.com", null));
        assertThat(p.getOfficialEmailOtpVerified()).isNull();
    }

    @Test
    void afterDraftChangedVerifiedFieldsAreInvalidated_includingAddress() {
        stubProfile(ApplicationStatus.KYC_PENDING);

        service.saveProfile(APP_ID, slice("New address", "Globex", null, null, null, null));

        verify(verificationInvalidation).invalidateForFields(eq(APP_ID),
                argThat(s -> s.equals(java.util.Set.of("employer", "address"))));
    }

    @Test
    void afterDraftOfficialEmailAndAccountChangesAreInvalidated() {
        CustomerProfile p = stubProfile(ApplicationStatus.KYC_PENDING);
        p.setSalaryAccountNumber("111");
        p.setSalaryIfsc("HDFC0000001");

        service.saveProfile(APP_ID, new ProfileRequest(null, null, null, null, null, null, null, null, null,
                null, "b@acme.com", "222", "icic0000002", null, null, null, null, null, null));

        verify(verificationInvalidation).invalidateForFields(eq(APP_ID),
                argThat(s -> s.equals(java.util.Set.of("officialEmail", "salaryAccountNumber", "salaryIfsc"))));
    }

    @Test
    void inDraftNothingIsInvalidated() {
        stubProfile(ApplicationStatus.DRAFT);

        service.saveProfile(APP_ID, slice(null, "Globex", 6_000_000L, null, null, null));

        verify(verificationInvalidation, never()).invalidateForFields(any(), any());
    }

    @Test
    void afterDraftABorrowerCannotMoveTheSalaryDay() {
        LoanApplication app = application();
        app.setStatus(ApplicationStatus.SANCTIONED);
        app.setSalaryCreditDay(5);
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        CustomerProfile p = new CustomerProfile();
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.of(p));
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.saveProfile(APP_ID, slice(null, null, null, null, null, java.time.LocalDate.of(2026, 10, 28)));

        assertThat(app.getSalaryCreditDay()).isEqualTo(5);
        assertThat(p.getPreviousSalaryDate()).isNull();
    }

    @Test
    void inDraftTheSalaryDayIsStillDerived() {
        LoanApplication app = application();
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.of(new CustomerProfile()));
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.saveProfile(APP_ID, slice(null, null, null, null, null, java.time.LocalDate.of(2026, 10, 28)));

        assertThat(app.getSalaryCreditDay()).isEqualTo(28);
    }

    @Test
    void afterDraftABorrowerCannotRaiseSalary_inEitherPath() {
        CustomerProfile p = stubProfile(ApplicationStatus.KYC_PENDING);

        assertThatThrownBy(() -> service.saveProfile(APP_ID, slice(null, null, 6_000_000L, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SALARY_INCREASE_NEEDS_REVIEW");
        assertThatThrownBy(() -> service.editOwnProfile(APP_ID, new EditProfileRequest(
                null, null, null, 6_000_000L, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SALARY_INCREASE_NEEDS_REVIEW");
        assertThat(p.getMonthlySalaryPaise()).isEqualTo(5_000_000L);
    }

    @Test
    void afterDraftABorrowerMayLowerSalary() {
        CustomerProfile p = stubProfile(ApplicationStatus.KYC_PENDING);

        service.saveProfile(APP_ID, slice(null, null, 4_000_000L, null, null, null));

        assertThat(p.getMonthlySalaryPaise()).isEqualTo(4_000_000L);
    }

    @Test
    void inDraftABorrowerMayRaiseSalary() {
        CustomerProfile p = stubProfile(ApplicationStatus.DRAFT);

        service.saveProfile(APP_ID, slice(null, null, 6_000_000L, null, null, null));

        assertThat(p.getMonthlySalaryPaise()).isEqualTo(6_000_000L);
    }

    // ---- V75: Aadhaar number slice ------------------------------------------------------

    private static ProfileRequest aadhaarReq(String aadhaar) {
        return new ProfileRequest(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, aadhaar);
    }

    @Test
    void storesAValidAadhaarWithCardSpacingStripped() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.empty());
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CustomerProfile saved = service.saveProfile(APP_ID, aadhaarReq("2345 6789 0124"));

        assertThat(saved.getAadhaar()).isEqualTo("234567890124");
    }

    @Test
    void refusesAnAadhaarThatFailsTheChecksum() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));

        assertThatThrownBy(() -> service.saveProfile(APP_ID, aadhaarReq("2345 6789 0125")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_AADHAAR");
        assertThatThrownBy(() -> service.saveProfile(APP_ID, aadhaarReq("12345678")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_AADHAAR");
    }

    @Test
    void aadhaarIsLockedOnceAVerificationHasReadIt_butTheSameValueMayBeResaved() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));
        CustomerProfile existing = new CustomerProfile();
        existing.setApplicationId(APP_ID);
        existing.setAadhaar("234567890124");
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.of(existing));
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        // The consent step has run the PAN check against the stored number.
        when(verificationRepository.findByApplicationIdAndCheckType(APP_ID, "PAN"))
                .thenReturn(Optional.of(new com.navix.loan.entity.ApplicationVerification()));

        assertThatThrownBy(() -> service.saveProfile(APP_ID, aadhaarReq("999988887779")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "AADHAAR_LOCKED");

        // Re-saving the identical number (a wizard re-render) is not a change and passes.
        assertThat(service.saveProfile(APP_ID, aadhaarReq("2345 6789 0124")).getAadhaar())
                .isEqualTo("234567890124");
    }

    @Test
    void aadhaarIsLockedOnceTheApplicationHasLeftDraft() {
        LoanApplication submitted = application();
        submitted.setStatus(ApplicationStatus.KYC_PENDING);
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(submitted));
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.of(new CustomerProfile()));

        assertThatThrownBy(() -> service.saveProfile(APP_ID, aadhaarReq("234567890124")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "AADHAAR_LOCKED");
    }

    @Test
    void anAadhaarHeldByAnotherCustomer_isFlaggedForStaffNotRefused() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));
        when(profileRepository.findOtherCustomerIdsByAadhaar("234567890124", CUSTOMER_ID))
                .thenReturn(java.util.List.of(11L, 12L));
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.empty());
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // The borrower's response is the ordinary success — nothing reveals the collision.
        CustomerProfile saved = service.saveProfile(APP_ID, aadhaarReq("234567890124"));
        assertThat(saved.getAadhaar()).isEqualTo("234567890124");

        var captor = org.mockito.ArgumentCaptor.forClass(com.navix.loan.entity.ApplicationVerification.class);
        verify(verificationRepository).save(captor.capture());
        assertThat(captor.getValue().getCheckType()).isEqualTo("AADHAAR_DUPLICATE");
        assertThat(captor.getValue().getStatus()).isEqualTo("REVIEW");
        assertThat(captor.getValue().getDerived()).isEqualTo("{\"otherCustomerIds\":[11,12]}");
        assertThat(captor.getValue().getMessage()).contains("#11, #12");
    }

    @Test
    void correctingTheNumberToOneNobodyElseHolds_clearsTheDuplicateFlag() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));
        CustomerProfile existing = new CustomerProfile();
        existing.setApplicationId(APP_ID);
        existing.setAadhaar("999988887779");
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.of(existing));
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        com.navix.loan.entity.ApplicationVerification flag = new com.navix.loan.entity.ApplicationVerification();
        // lenient: the editability guard also asks for PAN / AADHAAR rows (absent) with other args.
        org.mockito.Mockito.lenient()
                .when(verificationRepository.findByApplicationIdAndCheckType(APP_ID, "AADHAAR_DUPLICATE"))
                .thenReturn(Optional.of(flag));

        service.saveProfile(APP_ID, aadhaarReq("234567890124"));

        verify(verificationRepository).delete(flag);
        verify(verificationRepository, org.mockito.Mockito.never()).save(any());
    }

    // ---- document upload gate -------------------------------------------------------
    //
    // Uploading is wider than the rest of the review writes: the borrower plus the two credit
    // roles (and ADMIN by bypass). Every other staff role stays out, and deleting is untouched.

    private static DocumentRequest payslip() {
        return new DocumentRequest("PAYSLIP", "july.pdf", "application/pdf",
                Base64.getEncoder().encodeToString("payslip-bytes".getBytes()), null);
    }

    @Test
    void creditRolesMayUploadADocument() {
        when(applicationRepository.existsById(APP_ID)).thenReturn(true);
        when(documentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        for (CurrentActor actor : new CurrentActor[] {CREDIT_EXECUTIVE, CREDIT_HEAD}) {
            ActorContext.set(actor);
            ApplicationDocument saved = service.addDocument(APP_ID, payslip());
            assertThat(saved.getApplicationId()).isEqualTo(APP_ID);
            assertThat(saved.getDocType()).isEqualTo("PAYSLIP");
        }
    }

    @Test
    void aBorrowerMayUploadOnlyAllowListedDocTypes() {
        when(applicationRepository.existsById(APP_ID)).thenReturn(true);
        when(documentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ActorContext.set(BORROWER);

        ApplicationDocument saved = service.addDocument(APP_ID, new DocumentRequest("bank_statement", "s.pdf",
                "application/pdf", Base64.getEncoder().encodeToString("x".getBytes()), null));
        assertThat(saved.getDocType()).isEqualTo("BANK_STATEMENT");

        // System- and credit-team-owned types are never a borrower's to file.
        for (String reserved : new String[] {"CREDIT_BRIEF", "BUREAU_REPORT", "SIGNED_AGREEMENT", "SANCTION_LETTER"}) {
            assertThatThrownBy(() -> service.addDocument(APP_ID, new DocumentRequest(reserved, "x.pdf",
                    "application/pdf", Base64.getEncoder().encodeToString("x".getBytes()), null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Unsupported document type");
        }
    }

    @Test
    void otherStaffRolesMayNotUploadADocument() {
        ActorContext.set(ACCOUNTANT);

        assertThatThrownBy(() -> service.addDocument(APP_ID, payslip()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CREDIT_EXECUTIVE");
    }

    @Test
    void creditRolesMayNotDeleteADocument() {
        ActorContext.set(CREDIT_HEAD);

        assertThatThrownBy(() -> service.deleteDocument(APP_ID, 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void uploadedContentType_keepsRenderableTypes_andDowngradesScriptableOnes() {
        assertThat(CustomerReviewService.safeContentType("application/pdf")).isEqualTo("application/pdf");
        assertThat(CustomerReviewService.safeContentType("IMAGE/PNG")).isEqualTo("image/png");
        assertThat(CustomerReviewService.safeContentType("text/html")).isEqualTo("application/octet-stream");
        assertThat(CustomerReviewService.safeContentType("image/svg+xml")).isEqualTo("application/octet-stream");
        assertThat(CustomerReviewService.safeContentType(null)).isEqualTo("application/octet-stream");
    }

    @Test
    void borrowerCannotSeeTheCreditBriefOrBureauReport_staffStillCan() {
        when(applicationRepository.existsById(APP_ID)).thenReturn(true);
        ApplicationDocument card = doc(1L, "AADHAAR_CARD_FRONT");
        ApplicationDocument brief = doc(2L, CreditBriefService.DOC_TYPE);
        ApplicationDocument bureau = doc(3L, ApplicationVerificationService.BUREAU_REPORT);
        when(documentRepository.findByApplicationIdOrderByIdAsc(APP_ID)).thenReturn(List.of(card, brief, bureau));
        when(documentRepository.findByIdAndApplicationId(2L, APP_ID)).thenReturn(Optional.of(brief));

        ActorContext.set(BORROWER);
        assertThat(service.listDocuments(APP_ID)).containsExactly(card);
        assertThatThrownBy(() -> service.getDocument(APP_ID, 2L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.presignedUrl(APP_ID, 2L)).isInstanceOf(ResourceNotFoundException.class);

        ActorContext.set(CREDIT_EXECUTIVE);
        assertThat(service.listDocuments(APP_ID)).containsExactly(card, brief, bureau);
        assertThat(service.getDocument(APP_ID, 2L)).isSameAs(brief);
    }

    private static ApplicationDocument doc(Long id, String type) {
        ApplicationDocument d = new ApplicationDocument();
        d.setId(id);
        d.setApplicationId(APP_ID);
        d.setDocType(type);
        d.setS3ObjectKey("applications/" + APP_ID + "/" + type.toLowerCase() + "/1.pdf");
        return d;
    }
}
