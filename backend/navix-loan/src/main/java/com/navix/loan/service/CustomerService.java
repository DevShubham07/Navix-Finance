package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.CustomerDtos.CustomerDetail;
import com.navix.loan.dto.CustomerDtos.CustomerSummary;
import com.navix.loan.dto.CustomerDtos.UpdateCustomerRequest;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.dto.LoanDtos.PaymentView;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.ApplicantProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Borrower-centric ("customer") roll-up across the loan aggregate, keyed on the bigint
 * {@code applicant_id}. Lists/searches distinct applicants, returns a single applicant's full
 * history (profile + applications + loans + payments), and lets an ADMIN correct KYC data.
 *
 * <p>The {@code applicant_profile} row is 1:1 with an application, so a customer's name/PAN/mobile
 * come from their <b>latest</b> profile. The authoritative penalty/prepayment-aware balance from
 * {@link RepaymentService#outstandingAsOf} is reused so the figures match the repay page and
 * collections. Money is integer paise.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final LoanApplicationRepository applicationRepository;
    private final LoanRepository loanRepository;
    private final ApplicantProfileRepository profileRepository;
    private final PaymentRepository paymentRepository;
    private final RepaymentService repaymentService;

    /**
     * All customers (distinct applicants), optionally filtered by {@code q} matching the name
     * (case-insensitive contains) or the applicant id. Ordered by applicant id.
     */
    @Transactional(readOnly = true)
    public List<CustomerSummary> list(String q) {
        String needle = q != null ? q.trim().toLowerCase() : "";
        Map<Long, List<LoanApplication>> byApplicant = applicationRepository.findAll().stream()
                .collect(Collectors.groupingBy(LoanApplication::getApplicantId));

        List<CustomerSummary> out = new ArrayList<>();
        for (Map.Entry<Long, List<LoanApplication>> e : byApplicant.entrySet()) {
            Long applicantId = e.getKey();
            List<LoanApplication> apps = e.getValue();
            ApplicantProfile profile = latestProfile(apps);
            List<Loan> loans = loanRepository.findByApplicantId(applicantId);
            long totalOutstanding = loans.stream()
                    .mapToLong(l -> repaymentService.outstandingAsOf(l.getId(), null))
                    .sum();
            String latestStatus = apps.stream()
                    .max(Comparator.comparing(LoanApplication::getId))
                    .map(a -> a.getStatus().name())
                    .orElse(null);
            CustomerSummary cs = new CustomerSummary(
                    applicantId,
                    profile != null ? profile.getFullName() : null,
                    profile != null ? profile.getPan() : null,
                    profile != null ? profile.getMobile() : null,
                    apps.size(),
                    loans.size(),
                    latestStatus,
                    totalOutstanding,
                    profile != null && profile.getBureauScore() != null
                            ? profile.getBureauScore().intValue() : null,
                    profile != null && profile.getCreditStarRating() != null
                            ? profile.getCreditStarRating().doubleValue() : null);
            if (matches(cs, needle)) {
                out.add(cs);
            }
        }
        out.sort(Comparator.comparing(CustomerSummary::applicantId));
        return out;
    }

    /** A single customer's full history (newest first), or 404 if the applicant has nothing on file. */
    @Transactional(readOnly = true)
    public CustomerDetail detail(Long applicantId) {
        List<LoanApplication> apps = applicationRepository.findByApplicantId(applicantId);
        List<Loan> loans = loanRepository.findByApplicantId(applicantId);
        if (apps.isEmpty() && loans.isEmpty()) {
            throw new ResourceNotFoundException("Customer", String.valueOf(applicantId));
        }

        Map<Long, ApplicantProfile> profByApp = profileRepository
                .findByApplicationIdIn(apps.stream().map(LoanApplication::getId).toList()).stream()
                .collect(Collectors.toMap(ApplicantProfile::getApplicationId, p -> p, (a, b) -> a));
        List<ApplicationView> appViews = apps.stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed())
                .map(a -> ApplicationView.of(a, profByApp.get(a.getId())))
                .toList();

        LocalDate today = LocalDate.now();
        List<LoanView> loanViews = loans.stream()
                .sorted(Comparator.comparing(Loan::getId).reversed())
                .map(l -> LoanView.of(l, repaymentService.outstandingAsOf(l.getId(), null),
                        l.effectiveStatus(today)))
                .toList();

        List<PaymentView> payments = loans.stream()
                .flatMap(l -> paymentRepository.findByLoanId(l.getId()).stream())
                .sorted(Comparator.comparing(Payment::getId).reversed())
                .map(PaymentView::of)
                .toList();

        ApplicantProfile profile = latestProfile(apps);
        ProfileView profileView = profile != null ? ProfileView.of(profile) : null;
        return new CustomerDetail(applicantId, profileView, appViews, loanViews, payments);
    }

    /**
     * ADMIN-only correction of a customer's KYC data (non-identity fields). Updates the latest
     * profile; PAN/Aadhaar/mobile are left untouched (they hold uniqueness constraints).
     */
    @Transactional
    public ProfileView updateProfile(Long applicantId, UpdateCustomerRequest req) {
        requireAdmin();
        ApplicantProfile profile = latestProfile(applicationRepository.findByApplicantId(applicantId));
        if (profile == null) {
            throw new ResourceNotFoundException("ApplicantProfile", "applicant:" + applicantId);
        }
        profile.setFullName(trimToNull(req.fullName()));
        profile.setAddress(trimToNull(req.address()));
        profile.setEmployer(trimToNull(req.employer()));
        profile.setEmploymentStatus(trimToNull(req.employmentStatus()));
        profile.setMonthlySalaryPaise(req.monthlySalaryPaise());
        profile.setSalaryBank(trimToNull(req.salaryBank()));
        return ProfileView.of(profileRepository.save(profile));
    }

    // ---- internals -----------------------------------------------------------------

    /** The applicant's most recent saved KYC profile (newest application first), or null. */
    private ApplicantProfile latestProfile(List<LoanApplication> apps) {
        return apps.stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed())
                .map(a -> profileRepository.findByApplicationId(a.getId()).orElse(null))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static boolean matches(CustomerSummary cs, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        if (cs.name() != null && cs.name().toLowerCase().contains(needle)) {
            return true;
        }
        return String.valueOf(cs.applicantId()).contains(needle);
    }

    private static void requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
