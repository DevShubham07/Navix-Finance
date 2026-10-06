package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
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

    private CustomerReviewService service;

    @BeforeEach
    void setUp() {
        service = new CustomerReviewService(applicationRepository, profileRepository, documentRepository,
                storage, verificationInvalidation, eligibilityService, changeLogRepository, changeLogger,
                verificationRepository);
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
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.empty());
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // lower-case PAN, +91-prefixed mobile -> all normalised
        CustomerProfile saved = service.saveProfile(APP_ID, req("abcde1234f", "+91 98765 43210"));

        assertThat(saved.getApplicationId()).isEqualTo(APP_ID);
        assertThat(saved.getPan()).isEqualTo("ABCDE1234F");
        assertThat(saved.getMobile()).isEqualTo("9876543210");
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

        for (CurrentActor actor : new CurrentActor[] {CREDIT_EXECUTIVE, CREDIT_HEAD, BORROWER}) {
            ActorContext.set(actor);
            ApplicationDocument saved = service.addDocument(APP_ID, payslip());
            assertThat(saved.getApplicationId()).isEqualTo(APP_ID);
            assertThat(saved.getDocType()).isEqualTo("PAYSLIP");
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
}
