package com.navix.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.navix.common.exception.ResourceNotFoundException;
import com.navix.onboarding.domain.SignupStep;
import com.navix.onboarding.dto.OnboardingDtos.CreateBorrowerRequest;
import com.navix.onboarding.entity.Borrower;
import com.navix.onboarding.entity.SignupApplication;
import com.navix.onboarding.repository.BorrowerRepository;
import com.navix.onboarding.repository.SignupApplicationRepository;
import com.navix.onboarding.service.BorrowerService.SignupResult;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BorrowerServiceTest {

    @Mock
    private BorrowerRepository borrowerRepository;
    @Mock
    private SignupApplicationRepository signupApplicationRepository;

    private BorrowerService borrowerService;

    @BeforeEach
    void setUp() {
        borrowerService = new BorrowerService(borrowerRepository, signupApplicationRepository);
    }

    @Test
    void createPersistsBorrowerAndApplication() {
        when(borrowerRepository.save(any())).thenAnswer(i -> {
            Borrower b = i.getArgument(0);
            b.setId(11L);
            return b;
        });
        when(signupApplicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CreateBorrowerRequest request = new CreateBorrowerRequest(
                "9876543210", "ABCDE1234F", "ravi@gmail.com", "ravi@corp.com",
                "SALARIED", "100200300400", 5_000_000L, "HDFC", 30);

        SignupResult result = borrowerService.create(request);

        Borrower borrower = result.borrower();
        assertThat(borrower.getMobile()).isEqualTo("9876543210");
        assertThat(borrower.getPan()).isEqualTo("ABCDE1234F");
        assertThat(borrower.getDeclaredSalary()).isEqualTo(5_000_000L);
        assertThat(borrower.getStatus()).isEqualTo("SIGNUP_IN_PROGRESS");

        SignupApplication application = result.application();
        assertThat(application.getBorrowerId()).isEqualTo(11L);
        assertThat(application.getCurrentStep()).isEqualTo(SignupStep.MOBILE_OTP);
        assertThat(application.isCompleted()).isFalse();
    }

    @Test
    void advanceStepUpdatesCurrentStep() {
        SignupApplication application = new SignupApplication();
        application.setBorrowerId(11L);
        application.setCurrentStep(SignupStep.MOBILE_OTP);
        when(signupApplicationRepository.findByBorrowerId(11L)).thenReturn(Optional.of(application));
        when(signupApplicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SignupApplication advanced = borrowerService.advanceStep(11L, SignupStep.PAN_DETAILS, false);

        assertThat(advanced.getCurrentStep()).isEqualTo(SignupStep.PAN_DETAILS);
        assertThat(advanced.isCompleted()).isFalse();
    }

    @Test
    void advanceStepCompletionActivatesBorrower() {
        SignupApplication application = new SignupApplication();
        application.setBorrowerId(11L);
        application.setCurrentStep(SignupStep.REVIEW_SUBMIT);
        Borrower borrower = new Borrower();
        borrower.setId(11L);
        borrower.setStatus("SIGNUP_IN_PROGRESS");
        when(signupApplicationRepository.findByBorrowerId(11L)).thenReturn(Optional.of(application));
        when(signupApplicationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(borrowerRepository.findById(11L)).thenReturn(Optional.of(borrower));
        when(borrowerRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SignupApplication advanced = borrowerService.advanceStep(11L, SignupStep.REVIEW_SUBMIT, true);

        assertThat(advanced.isCompleted()).isTrue();
        assertThat(borrower.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void getBorrowerReturnsWhenPresent() {
        Borrower borrower = new Borrower();
        borrower.setId(11L);
        when(borrowerRepository.findById(11L)).thenReturn(Optional.of(borrower));

        assertThat(borrowerService.getBorrower(11L)).isSameAs(borrower);
    }

    @Test
    void getBorrowerThrowsWhenMissing() {
        when(borrowerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> borrowerService.getBorrower(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
