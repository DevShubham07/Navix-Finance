package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.risk.RiskPort;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.CustomerDtos.ActivityEntry;
import com.navix.loan.dto.CustomerDtos.CustomerDetail;
import com.navix.loan.dto.CustomerDtos.CustomerSummary;
import com.navix.loan.dto.CustomerDtos.ProfileChangeView;
import com.navix.loan.dto.CustomerDtos.RemarkView;
import com.navix.loan.dto.CustomerDtos.UpdateCustomerRequest;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.dto.LoanDtos.PaymentView;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.CustomerRemark;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Payment;
import com.navix.loan.entity.ProfileChangeLog;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.CustomerRemarkRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.repository.ProfileChangeLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Borrower-centric ("customer") roll-up across the loan aggregate, keyed on the bigint
 * {@code customer_id}. Lists/searches distinct customers, returns a single customer's full
 * history (profile + applications + loans + payments), and lets an ADMIN correct KYC data.
 *
 * <p>The {@code customer_profile} row is 1:1 with an application, so a customer's name/PAN/mobile
 * come from their <b>latest</b> profile. The authoritative penalty/prepayment-aware balance from
 * {@link RepaymentService#outstandingAsOf} is reused so the figures match the repay page and
 * collections. Money is integer paise.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final LoanApplicationRepository applicationRepository;
    private final LoanRepository loanRepository;
    private final CustomerProfileRepository profileRepository;
    private final PaymentRepository paymentRepository;
    private final RepaymentService repaymentService;
    private final ProfileChangeLogRepository changeLogRepository;
    private final ApplicationEventRepository applicationEventRepository;
    private final CustomerRemarkRepository remarkRepository;
    private final RiskPort risk;

    /**
     * All customers (distinct customers), optionally filtered by {@code q} matching the name
     * (case-insensitive contains) or the customer id. Ordered by customer id.
     */
    @Transactional(readOnly = true)
    public List<CustomerSummary> list(String q) {
        String needle = q != null ? q.trim().toLowerCase() : "";
        Map<Long, List<LoanApplication>> byCustomer = applicationRepository.findAll().stream()
                .collect(Collectors.groupingBy(LoanApplication::getCustomerId));

        List<CustomerSummary> out = new ArrayList<>();
        for (Map.Entry<Long, List<LoanApplication>> e : byCustomer.entrySet()) {
            Long customerId = e.getKey();
            List<LoanApplication> apps = e.getValue();
            CustomerProfile profile = latestProfile(apps);
            List<Loan> loans = loanRepository.findByCustomerId(customerId);
            long totalOutstanding = loans.stream()
                    .mapToLong(l -> repaymentService.outstandingAsOf(l.getId(), null))
                    .sum();
            String latestStatus = apps.stream()
                    .max(Comparator.comparing(LoanApplication::getId))
                    .map(a -> a.getStatus().name())
                    .orElse(null);
            CustomerSummary cs = new CustomerSummary(
                    customerId,
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
        out.sort(Comparator.comparing(CustomerSummary::customerId));
        return out;
    }

    /** A single customer's full history (newest first), or 404 if the customer has nothing on file. */
    @Transactional(readOnly = true)
    public CustomerDetail detail(Long customerId) {
        List<LoanApplication> apps = applicationRepository.findByCustomerId(customerId);
        List<Loan> loans = loanRepository.findByCustomerId(customerId);
        if (apps.isEmpty() && loans.isEmpty()) {
            throw new ResourceNotFoundException("Customer", String.valueOf(customerId));
        }

        Map<Long, CustomerProfile> profByApp = profileRepository
                .findByApplicationIdIn(apps.stream().map(LoanApplication::getId).toList()).stream()
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
        // Every application here belongs to this one customer, so an application without its OWN profile
        // snapshot (e.g. a reborrow) falls back to the customer's latest profile — keeping the per-row
        // credit headline consistent with the Profile card.
        CustomerProfile profile = latestProfile(apps);
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
        return new CustomerDetail(customerId, profileView, appViews, loanViews, payments);
    }

    /**
     * ADMIN-only correction of a customer's KYC / salary data (non-identity fields). Updates the latest
     * profile; PAN/Aadhaar/mobile are left untouched (they hold uniqueness constraints). Every changed
     * field is recorded to the {@link ProfileChangeLog} (previous→new, who, when), and a salary change
     * recomputes the eligible limit on the customer's not-yet-disbursed applications.
     */
    @Transactional
    public ProfileView updateProfile(Long customerId, UpdateCustomerRequest req) {
        requireAdmin();
        CustomerProfile profile = latestProfile(applicationRepository.findByCustomerId(customerId));
        if (profile == null) {
            throw new ResourceNotFoundException("CustomerProfile", "customer:" + customerId);
        }
        Long appId = profile.getApplicationId();
        Long oldSalary = profile.getMonthlySalaryPaise();

        String fullName = trimToNull(req.fullName());
        logIfChanged(customerId, appId, "fullName", profile.getFullName(), fullName);
        profile.setFullName(fullName);

        String address = trimToNull(req.address());
        logIfChanged(customerId, appId, "address", profile.getAddress(), address);
        profile.setAddress(address);

        String employer = trimToNull(req.employer());
        logIfChanged(customerId, appId, "employer", profile.getEmployer(), employer);
        profile.setEmployer(employer);

        String employmentStatus = trimToNull(req.employmentStatus());
        logIfChanged(customerId, appId, "employmentStatus", profile.getEmploymentStatus(), employmentStatus);
        profile.setEmploymentStatus(employmentStatus);

        String salaryBank = trimToNull(req.salaryBank());
        logIfChanged(customerId, appId, "salaryBank", profile.getSalaryBank(), salaryBank);
        profile.setSalaryBank(salaryBank);

        logIfChanged(customerId, appId, "monthlySalaryPaise", str(oldSalary), str(req.monthlySalaryPaise()));
        profile.setMonthlySalaryPaise(req.monthlySalaryPaise());

        logIfChanged(customerId, appId, "annualSalaryPaise", str(profile.getAnnualSalaryPaise()), str(req.annualSalaryPaise()));
        profile.setAnnualSalaryPaise(req.annualSalaryPaise());

        logIfChanged(customerId, appId, "salaryPercentage", str(profile.getSalaryPercentage()), str(req.salaryPercentage()));
        profile.setSalaryPercentage(req.salaryPercentage());

        logIfChanged(customerId, appId, "incrementPercentage", str(profile.getIncrementPercentage()), str(req.incrementPercentage()));
        profile.setIncrementPercentage(req.incrementPercentage());

        CustomerProfile saved = profileRepository.save(profile);

        if (!Objects.equals(oldSalary, saved.getMonthlySalaryPaise())) {
            recomputeEligibility(customerId, saved.getMonthlySalaryPaise());
        }
        return ProfileView.of(saved);
    }

    /** One customer's audited profile/salary change history (newest first). Staff-readable. */
    @Transactional(readOnly = true)
    public List<ProfileChangeView> changeHistory(Long customerId) {
        return changeLogRepository.findByCustomerIdOrderByIdDesc(customerId).stream()
                .map(ProfileChangeView::of)
                .toList();
    }

    /**
     * Unified customer activity timeline (newest first): every lifecycle transition + KYC re-verify
     * (from {@code application_event} across the customer's applications), every profile/salary edit
     * (from {@code profile_change_log}), and every staff remark — merged and sorted by timestamp. Backs
     * the "All past logs" tab of the customer detail popup.
     */
    @Transactional(readOnly = true)
    public List<ActivityEntry> activity(Long customerId) {
        List<ActivityEntry> out = new ArrayList<>();

        // 1. Lifecycle + re-verify events across every application this customer owns.
        for (LoanApplication a : applicationRepository.findByCustomerId(customerId)) {
            for (ApplicationEvent e : applicationEventRepository.findByApplicationIdOrderByAtAsc(a.getId())) {
                boolean reverify = "REVERIFY".equals(e.getAction());
                String from = e.getFromStatus() != null ? e.getFromStatus().name() : null;
                String to = e.getToStatus() != null ? e.getToStatus().name() : null;
                String detail = reverify
                        ? (e.getNotes() != null ? e.getNotes() : "Verification reset for re-check")
                        : ((from != null ? from + " → " : "") + (to != null ? to : "")
                                + (e.getNotes() != null ? " · " + e.getNotes() : ""));
                out.add(new ActivityEntry(
                        reverify ? "REVERIFY" : "LIFECYCLE",
                        a.getId(),
                        humanize(e.getAction()),
                        detail.isBlank() ? null : detail,
                        e.getActorRole(),
                        e.getAt()));
            }
        }

        // 2. Profile / salary edits (carry the new value + who + when).
        for (ProfileChangeLog c : changeLogRepository.findByCustomerIdOrderByIdDesc(customerId)) {
            out.add(new ActivityEntry(
                    "PROFILE",
                    c.getApplicationId(),
                    "Updated " + humanize(c.getField()),
                    (c.getOldValue() != null ? c.getOldValue() : "—") + " → "
                            + (c.getNewValue() != null ? c.getNewValue() : "—"),
                    c.getCreatedBy(),
                    c.getCreatedAt()));
        }

        // 3. Staff remarks.
        for (CustomerRemark r : remarkRepository.findByCustomerIdOrderByIdDesc(customerId)) {
            out.add(new ActivityEntry("REMARK", null, "Remark", r.getBody(), r.getCreatedBy(), r.getCreatedAt()));
        }

        out.sort(Comparator.comparing(ActivityEntry::at,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** One customer's staff remarks (newest first). */
    @Transactional(readOnly = true)
    public List<RemarkView> remarks(Long customerId) {
        return remarkRepository.findByCustomerIdOrderByIdDesc(customerId).stream()
                .map(RemarkView::of)
                .toList();
    }

    /** Add a staff remark to a customer (author + timestamp captured by JPA auditing). */
    @Transactional
    public RemarkView addRemark(Long customerId, String body) {
        CustomerRemark r = new CustomerRemark();
        r.setCustomerId(customerId);
        r.setBody(body.trim());
        return RemarkView.of(remarkRepository.save(r));
    }

    /** "monthlySalaryPaise"/"KYC_CREDIT_APPROVE" → "Monthly salary paise"/"Kyc credit approve". */
    private static String humanize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Update";
        }
        String spaced = raw
                .replace('_', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .trim()
                .toLowerCase();
        return spaced.substring(0, 1).toUpperCase() + spaced.substring(1);
    }

    // ---- internals -----------------------------------------------------------------

    /** Append a change-log row when {@code old != new} (no-op when unchanged). */
    private void logIfChanged(Long customerId, Long applicationId, String field, String oldVal, String newVal) {
        if (Objects.equals(oldVal, newVal)) {
            return;
        }
        ProfileChangeLog entry = new ProfileChangeLog();
        entry.setCustomerId(customerId);
        entry.setApplicationId(applicationId);
        entry.setField(field);
        entry.setOldValue(oldVal);
        entry.setNewValue(newVal);
        changeLogRepository.save(entry);
    }

    /**
     * Recompute the eligible limit (RiskPort's firm 25%-of-salary cap) on the customer's
     * <b>not-yet-disbursed</b> applications, so an admin salary edit propagates to eligibility. A
     * disbursed loan's limit is historical and left untouched.
     */
    private void recomputeEligibility(Long customerId, Long monthlySalaryPaise) {
        if (monthlySalaryPaise == null || monthlySalaryPaise <= 0) {
            return;
        }
        long eligible = risk.eligibleLimitPaise(monthlySalaryPaise);
        for (LoanApplication a : applicationRepository.findByCustomerId(customerId)) {
            if (a.getLoanId() == null) {
                a.setEligibleLimit(eligible);
                applicationRepository.save(a);
            }
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** The customer's most recent saved KYC profile (newest application first), or null. */
    private CustomerProfile latestProfile(List<LoanApplication> apps) {
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
        return String.valueOf(cs.customerId()).contains(needle);
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
