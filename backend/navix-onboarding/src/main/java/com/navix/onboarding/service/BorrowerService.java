package com.navix.onboarding.service;

import com.navix.common.exception.ResourceNotFoundException;
import com.navix.onboarding.domain.SignupStep;
import com.navix.onboarding.dto.OnboardingDtos.CreateBorrowerRequest;
import com.navix.onboarding.entity.Borrower;
import com.navix.onboarding.entity.SignupApplication;
import com.navix.onboarding.repository.BorrowerRepository;
import com.navix.onboarding.repository.SignupApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Borrower sign-up and profile orchestration.
 *
 * <p>Creating a borrower also opens a {@link SignupApplication} positioned at the first step
 * ({@link SignupStep#MOBILE_OTP}). The application can then be advanced step-by-step and marked
 * complete at the end of the 12-step flow. All salary money is integer paise.
 */
@Service
@RequiredArgsConstructor
public class BorrowerService {

    private final BorrowerRepository borrowerRepository;
    private final SignupApplicationRepository signupApplicationRepository;

    /**
     * Create a {@link Borrower} from the sign-up request and open its {@link SignupApplication}
     * at {@link SignupStep#MOBILE_OTP}. The created application is returned alongside the borrower
     * via {@link SignupResult}.
     */
    @Transactional
    public SignupResult create(CreateBorrowerRequest request) {
        Borrower borrower = new Borrower();
        borrower.setMobile(request.mobile());
        borrower.setPan(request.pan());
        borrower.setPersonalEmail(request.personalEmail());
        borrower.setOfficialEmail(request.officialEmail());
        borrower.setEmploymentStatus(request.employmentStatus());
        borrower.setUan(request.uan());
        borrower.setDeclaredSalary(request.declaredSalaryPaise());
        borrower.setSalaryBank(request.salaryBank());
        borrower.setSalaryCreditDay(request.salaryCreditDay());
        borrower.setStatus("SIGNUP_IN_PROGRESS");
        Borrower savedBorrower = borrowerRepository.save(borrower);

        SignupApplication application = new SignupApplication();
        application.setBorrowerId(savedBorrower.getId());
        application.setCurrentStep(SignupStep.MOBILE_OTP);
        application.setCompleted(false);
        SignupApplication savedApplication = signupApplicationRepository.save(application);

        return new SignupResult(savedBorrower, savedApplication);
    }

    /**
     * Advance the borrower's sign-up application to {@code step}. When {@code completed} is true
     * the application (and borrower) are marked done.
     */
    @Transactional
    public SignupApplication advanceStep(Long borrowerId, SignupStep step, boolean completed) {
        SignupApplication application = signupApplicationRepository.findByBorrowerId(borrowerId)
                .orElseThrow(() -> new ResourceNotFoundException("SignupApplication",
                        "borrowerId=" + borrowerId));
        application.setCurrentStep(step);
        application.setCompleted(completed);
        SignupApplication saved = signupApplicationRepository.save(application);
        if (completed) {
            borrowerRepository.findById(borrowerId).ifPresent(b -> {
                b.setStatus("ACTIVE");
                borrowerRepository.save(b);
            });
        }
        return saved;
    }

    /** Load a borrower's profile, 404 if missing. */
    @Transactional(readOnly = true)
    public Borrower getBorrower(Long borrowerId) {
        return borrowerRepository.findById(borrowerId)
                .orElseThrow(() -> new ResourceNotFoundException("Borrower", String.valueOf(borrowerId)));
    }

    /** A newly created borrower together with its freshly opened sign-up application. */
    public record SignupResult(Borrower borrower, SignupApplication application) {
    }
}
