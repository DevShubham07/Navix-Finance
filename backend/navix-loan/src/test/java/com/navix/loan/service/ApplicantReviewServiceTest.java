package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.dto.ReviewDtos.ProfileRequest;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.repository.ApplicantProfileRepository;
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
class ApplicantReviewServiceTest {

    private static final long APP_ID = 4L;
    private static final CurrentActor BORROWER =
            new CurrentActor("9001001", "Asha Verma", "BORROWER");

    @Mock
    private LoanApplicationRepository applicationRepository;
    @Mock
    private ApplicantProfileRepository profileRepository;
    @Mock
    private ApplicationDocumentRepository documentRepository;
    @Mock
    private DocumentStoragePort storage;

    private ApplicantReviewService service;

    @BeforeEach
    void setUp() {
        service = new ApplicantReviewService(applicationRepository, profileRepository, documentRepository, storage);
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

    @Test
    void rejectsDuplicatePan() {
        when(applicationRepository.existsById(APP_ID)).thenReturn(true);
        when(profileRepository.existsByPanAndApplicationIdNot("ABCDE1234F", APP_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.saveProfile(APP_ID, req("ABCDE1234F", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PAN");
    }

    @Test
    void rejectsDuplicateAadhaar() {
        when(applicationRepository.existsById(APP_ID)).thenReturn(true);
        when(profileRepository.existsByPanAndApplicationIdNot("ABCDE1234F", APP_ID)).thenReturn(false);
        when(profileRepository.existsByAadhaarAndApplicationIdNot("123456789012", APP_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.saveProfile(APP_ID, req("ABCDE1234F", "1234 5678 9012", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Aadhaar");
    }

    @Test
    void rejectsDuplicateMobile() {
        when(applicationRepository.existsById(APP_ID)).thenReturn(true);
        when(profileRepository.existsByPanAndApplicationIdNot("ABCDE1234F", APP_ID)).thenReturn(false);
        when(profileRepository.existsByAadhaarAndApplicationIdNot("123456789012", APP_ID)).thenReturn(false);
        when(profileRepository.existsByMobileAndApplicationIdNot("9876543210", APP_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.saveProfile(APP_ID, req("ABCDE1234F", "123456789012", "98765 43210")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("mobile");
    }

    @Test
    void rejectsMalformedAadhaar() {
        when(applicationRepository.existsById(APP_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.saveProfile(APP_ID, req("ABCDE1234F", "123", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("12-digit");
    }

    @Test
    void acceptsUniqueIdentityAndNormalises() {
        when(applicationRepository.existsById(APP_ID)).thenReturn(true);
        when(profileRepository.existsByPanAndApplicationIdNot("ABCDE1234F", APP_ID)).thenReturn(false);
        when(profileRepository.existsByAadhaarAndApplicationIdNot("123456789012", APP_ID)).thenReturn(false);
        when(profileRepository.existsByMobileAndApplicationIdNot("9876543210", APP_ID)).thenReturn(false);
        when(profileRepository.findByApplicationId(APP_ID)).thenReturn(Optional.empty());
        when(profileRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // lower-case PAN, spaced Aadhaar, +91-prefixed mobile -> all normalised
        ApplicantProfile saved = service.saveProfile(APP_ID, req("abcde1234f", "1234-5678-9012", "+91 98765 43210"));

        assertThat(saved.getApplicationId()).isEqualTo(APP_ID);
        assertThat(saved.getPan()).isEqualTo("ABCDE1234F");
        assertThat(saved.getAadhaar()).isEqualTo("123456789012");
        assertThat(saved.getMobile()).isEqualTo("9876543210");
    }
}
