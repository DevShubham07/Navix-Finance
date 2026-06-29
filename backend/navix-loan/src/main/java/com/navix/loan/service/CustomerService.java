package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.risk.RiskPort;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.CustomerDtos.CustomerDetail;
import com.navix.loan.dto.CustomerDtos.CustomerSummary;
import com.navix.loan.dto.CustomerDtos.ProfileChangeView;
import com.navix.loan.dto.CustomerDtos.UpdateCustomerRequest;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.dto.LoanDtos.PaymentView;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Payment;
import com.navix.loan.entity.ProfileChangeLog;
import com.navix.loan.repository.ApplicantProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.repository.ProfileChangeLogRepository;
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
    private final ProfileChangeLogRepository changeLogRepository;
    private final RiskPort risk;

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
        // Every application here belongs to this one customer, so an application without its OWN profile
        // snapshot (e.g. a reborrow) falls back to the customer's latest profile — keeping the per-row
        // credit headline consistent with the Profile card.
        ApplicantProfile profile = latestProfile(apps);
        List<ApplicationView> appViews = apps.stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed())
                .map(a -> ApplicationView.of(a, profByApp.getOrDefault(a.getId(), profile)))
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

        ProfileView profileView = profile != null ? ProfileView.of(profile) : null;
        return new CustomerDetail(applicantId, profileView, appViews, loanViews, payments);
    }

    /**
     * ADMIN-only correction of a customer's KYC / salary data (non-identity fields). Updates the latest
     * profile; PAN/Aadhaar/mobile are left untouched (they hold uniqueness constraints). Every changed
     * field is recorded to the {@link ProfileChangeLog} (previous→new, who, when), and a salary change
     * recomputes the eligible limit on the applicant's not-yet-disbursed applications.
     */
    @Transactional
    public ProfileView updateProfile(Long applicantId, UpdateCustomerRequest req) {
        requireAdmin();
        ApplicantProfile profile = latestProfile(applicationRepository.findByApplicantId(applicantId));
        if (profile == null) {
            throw new ResourceNotFoundException("ApplicantProfile", "applicant:" + applicantId);
        }
        Long appId = profile.getApplicationId();
        Long oldSalary = profile.getMonthlySalaryPaise();

        String fullName = trimToNull(req.fullName());
        logIfChanged(applicantId, appId, "fullName", profile.getFullName(), fullName);
        profile.setFullName(fullName);

        String address = trimToNull(req.address());
        logIfChanged(applicantId, appId, "address", profile.getAddress(), address);
        profile.setAddress(address);

        String employer = trimToNull(req.employer());
        logIfChanged(applicantId, appId, "employer", profile.getEmployer(), employer);
        profile.setEmployer(employer);

        String employmentStatus = trimToNull(req.employmentStatus());
        logIfChanged(applicantId, appId, "employmentStatus", profile.getEmploymentStatus(), employmentStatus);
        profile.setEmploymentStatus(employmentStatus);

        String salaryBank = trimToNull(req.salaryBank());
        logIfChanged(applicantId, appId, "salaryBank", profile.getSalaryBank(), salaryBank);
        profile.setSalaryBank(salaryBank);

        logIfChanged(applicantId, appId, "monthlySalaryPaise", str(oldSalary), str(req.monthlySalaryPaise()));
        profile.setMonthlySalaryPaise(req.monthlySalaryPaise());

        logIfChanged(applicantId, appId, "annualSalaryPaise", str(profile.getAnnualSalaryPaise()), str(req.annualSalaryPaise()));
        profile.setAnnualSalaryPaise(req.annualSalaryPaise());

        logIfChanged(applicantId, appId, "salaryPercentage", str(profile.getSalaryPercentage()), str(req.salaryPercentage()));
        profile.setSalaryPercentage(req.salaryPercentage());

        logIfChanged(applicantId, appId, "incrementPercentage", str(profile.getIncrementPercentage()), str(req.incrementPercentage()));
        profile.setIncrementPercentage(req.incrementPercentage());

        ApplicantProfile saved = profileRepository.save(profile);

        if (!Objects.equals(oldSalary, saved.getMonthlySalaryPaise())) {
            recomputeEligibility(applicantId, saved.getMonthlySalaryPaise());
        }
        return ProfileView.of(saved);
    }

    /** One customer's audited profile/salary change history (newest first). Staff-readable. */
    @Transactional(readOnly = true)
    public List<ProfileChangeView> changeHistory(Long applicantId) {
        return changeLogRepository.findByApplicantIdOrderByIdDesc(applicantId).stream()
                .map(ProfileChangeView::of)
                .toList();
    }

    // ---- internals -----------------------------------------------------------------

    /** Append a change-log row when {@code old != new} (no-op when unchanged). */
    private void logIfChanged(Long applicantId, Long applicationId, String field, String oldVal, String newVal) {
        if (Objects.equals(oldVal, newVal)) {
            return;
        }
        ProfileChangeLog entry = new ProfileChangeLog();
        entry.setApplicantId(applicantId);
        entry.setApplicationId(applicationId);
        entry.setField(field);
        entry.setOldValue(oldVal);
        entry.setNewValue(newVal);
        changeLogRepository.save(entry);
    }

    /**
     * Recompute the eligible limit (RiskPort's firm 25%-of-salary cap) on the applicant's
     * <b>not-yet-disbursed</b> applications, so an admin salary edit propagates to eligibility. A
     * disbursed loan's limit is historical and left untouched.
     */
    private void recomputeEligibility(Long applicantId, Long monthlySalaryPaise) {
        if (monthlySalaryPaise == null || monthlySalaryPaise <= 0) {
            return;
        }
        long eligible = risk.eligibleLimitPaise(monthlySalaryPaise);
        for (LoanApplication a : applicationRepository.findByApplicantId(applicantId)) {
            if (a.getLoanId() == null) {
                a.setEligibleLimit(eligible);
                applicationRepository.save(a);
            }
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

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
