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
import com.navix.loan.dto.ReviewDtos.EditProfileRequest;
import com.navix.loan.dto.ReviewDtos.ProfileRequest;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.LoanApplicationRepository;
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

    private CustomerReviewService service;

    @BeforeEach
    void setUp() {
        service = new CustomerReviewService(applicationRepository, profileRepository, documentRepository,
                storage, verificationInvalidation, eligibilityService);
        ActorContext.set(BORROWER);
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private ProfileRequest req(String pan, String aadhaar, String mobile) {
        return new ProfileRequest("Asha Verma", pan, aadhaar, mobile,
                null, null, null, null, null, null, null);
    }

    /** A persisted application owned by CUSTOMER_ID — saveProfile now resolves the customer via findById. */
    private LoanApplication application() {
        LoanApplication app = new LoanApplication();
        app.setId(APP_ID);
        app.setCustomerId(CUSTOMER_ID);
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
                "New address", null, null, 6_000_000L, null, null, "Mom", "9990001111", "Mother"));

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

        assertThatThrownBy(() -> service.saveProfile(APP_ID, req("ABCDE1234F", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PAN");
    }

    @Test
    void rejectsDuplicateAadhaar() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));
        when(profileRepository.existsPanForOtherCustomer("ABCDE1234F", CUSTOMER_ID)).thenReturn(false);
        when(profileRepository.existsAadhaarForOtherCustomer("123456789012", CUSTOMER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.saveProfile(APP_ID, req("ABCDE1234F", "1234 5678 9012", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Aadhaar");
    }

    @Test
    void rejectsDuplicateMobile() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));
        when(profileRepository.existsPanForOtherCustomer("ABCDE1234F", CUSTOMER_ID)).thenReturn(false);
        when(profileRepository.existsAadhaarForOtherCustomer("123456789012", CUSTOMER_ID)).thenReturn(false);
        when(profileRepository.existsMobileForOtherCustomer("9876543210", CUSTOMER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.saveProfile(APP_ID, req("ABCDE1234F", "123456789012", "98765 43210")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("mobile");
    }

    @Test
    void rejectsMalformedAadhaar() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));

        assertThatThrownBy(() -> service.saveProfile(APP_ID, req("ABCDE1234F", "123", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("12-digit");
    }

    @Test
    void acceptsUniqueIdentityAndNormalises() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(application()));
        when(profileRepository.existsPanForOtherCustomer("ABCDE1234F", CUSTOMER_ID)).thenReturn(false);
        when(profileRepository.existsAadhaarForOtherCustomer("123456789012", CUSTOMER_ID)).thenReturn(false);
        when(profileRepository.existsMobileForOtherCustomer("9876543210", CUSTOMER_ID)).thenReturn(false);
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.empty());
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // lower-case PAN, spaced Aadhaar, +91-prefixed mobile -> all normalised
        CustomerProfile saved = service.saveProfile(APP_ID, req("abcde1234f", "1234-5678-9012", "+91 98765 43210"));

        assertThat(saved.getApplicationId()).isEqualTo(APP_ID);
        assertThat(saved.getPan()).isEqualTo("ABCDE1234F");
        assertThat(saved.getAadhaar()).isEqualTo("123456789012");
        assertThat(saved.getMobile()).isEqualTo("9876543210");
    }
}
