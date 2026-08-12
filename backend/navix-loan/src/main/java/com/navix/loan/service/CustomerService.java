package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.risk.RiskPort;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.CustomerDtos.ActivityEntry;
import com.navix.loan.dto.CustomerDtos.AddCallLogRequest;
import com.navix.loan.dto.CustomerDtos.ApplicationDocumentGroup;
import com.navix.loan.dto.CustomerDtos.CallLogView;
import com.navix.loan.dto.CreditBriefDtos.CreditBriefView;
import com.navix.loan.dto.CustomerDtos.CustomerDetail;
import com.navix.loan.dto.CustomerDtos.CustomerSummary;
import com.navix.loan.dto.CustomerDtos.ProfileChangeView;
import com.navix.loan.dto.CustomerDtos.RemarkView;
import com.navix.loan.dto.CustomerDtos.UpdateCustomerRequest;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.dto.LoanDtos.PaymentView;
import com.navix.loan.dto.ReviewDtos.DocumentView;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.CustomerCallLog;
import com.navix.loan.entity.CustomerOwner;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.CustomerRemark;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Payment;
import com.navix.loan.entity.ProfileChangeLog;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.CustomerCallLogRepository;
import com.navix.loan.repository.CustomerOwnerRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.CustomerRemarkRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.repository.ProfileChangeLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Borrower-centric ("customer") roll-up across the loan aggregate, keyed on the bigint
 * {@code customer_id}. Lists/searches distinct customers, returns a single customer's full
 * history (profile + applications + loans + payments), ownership, call logs, and lets an ADMIN
 * correct KYC data.
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
    private final CustomerOwnerRepository ownerRepository;
    private final CustomerCallLogRepository callLogRepository;
    private final StaffDirectory staffDirectory;
    private final RiskPort risk;
    private final JdbcTemplate jdbc;
    private final CreditBriefService creditBriefService;
    private final ApplicationDocumentRepository documentRepository;

    /**
     * All customers (distinct customers), optionally filtered by {@code q} matching the name
     * (case-insensitive contains), PAN, mobile, or the customer id. Ordered by customer id.
     */
    @Transactional(readOnly = true)
    public List<CustomerSummary> list(String q) {
        // ponytail: whole-table rollup + client-side segmenting. Move to a paged indexed query when the
        // list stops fitting one response — same change as adding server-side segment filters.
        String needle = q != null ? q.trim().toLowerCase() : "";
        Map<Long, List<LoanApplication>> byCustomer = applicationRepository.findAll().stream()
                .collect(Collectors.groupingBy(LoanApplication::getCustomerId));

        Map<Long, CustomerOwner> owners = ownerRepository.findAll().stream()
                .collect(Collectors.toMap(CustomerOwner::getCustomerId, o -> o, (a, b) -> a));
        Map<Long, String> staffNames = new HashMap<>();

        LocalDate today = LocalDate.now();
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
            String loanStatus = loans.stream()
                    .max(Comparator.comparing(Loan::getId))
                    .map(l -> l.effectiveStatus(today).name())
                    .orElse(null);
            CustomerOwner owner = owners.get(customerId);
            Long ownerStaffId = owner != null ? owner.getOwnerStaffId() : null;
            String ownerName = ownerStaffId != null ? staffName(ownerStaffId, staffNames) : null;
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
                            ? profile.getCreditStarRating().doubleValue() : null,
                    loanStatus,
                    ownerStaffId,
                    ownerName);
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
        CustomerOwner owner = ownerRepository.findById(customerId).orElse(null);
        Long ownerStaffId = owner != null ? owner.getOwnerStaffId() : null;
        String ownerName = ownerStaffId != null
                ? staffDirectory.findStaff(ownerStaffId).map(StaffSummary::name).orElse(null)
                : null;
        // The full categorized brief (facts + PDF doc id) for the latest application — reuses
        // CreditBriefService.view() as-is (null-safe: returns an available=false shell, never throws,
        // when no bureau pull has happened yet) so the Customer roll-up and the per-application Credit
        // Report tab always agree.
        Long latestAppId = appViews.isEmpty() ? null : appViews.get(0).id();
        CreditBriefView creditBrief = latestAppId != null ? creditBriefService.view(latestAppId) : null;
        return new CustomerDetail(customerId, profileView, appViews, loanViews, payments,
                ownerStaffId, ownerName, creditBrief);
    }

    /**
     * Every document across ALL of this customer's applications, grouped by application (newest
     * application first) — work item 4. Every customer-first entry point elsewhere pins to the
     * newest application ({@code applications[0]}), so on a reborrow the prior application's uploads
     * became unreachable through those surfaces; this endpoint is the fix.
     */
    @Transactional(readOnly = true)
    public List<ApplicationDocumentGroup> documents(Long customerId) {
        List<LoanApplication> apps = applicationRepository.findByCustomerId(customerId);
        if (apps.isEmpty()) {
            throw new ResourceNotFoundException("Customer", String.valueOf(customerId));
        }
        List<Long> appIds = apps.stream().map(LoanApplication::getId).toList();
        Map<Long, ApplicationStatus> statusByApp = apps.stream()
                .collect(Collectors.toMap(LoanApplication::getId, LoanApplication::getStatus));
        // Already ordered applicationId desc, id asc by the repository method.
        Map<Long, List<ApplicationDocument>> byApp = new java.util.LinkedHashMap<>();
        for (ApplicationDocument d : documentRepository
                .findByApplicationIdInOrderByApplicationIdDescIdAsc(appIds)) {
            byApp.computeIfAbsent(d.getApplicationId(), k -> new ArrayList<>()).add(d);
        }
        return apps.stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed())
                .map(a -> new ApplicationDocumentGroup(a.getId(), statusByApp.get(a.getId()),
                        byApp.getOrDefault(a.getId(), List.of()).stream().map(DocumentView::of).toList()))
                .toList();
    }

    /**
     * Assign (or clear) the staff owner of a customer. CREDIT_HEAD / COLLECTION_HEAD / TELECALLER /
     * ADMIN — TELECALLER added for work item 10's "Assign to me" self-assignment on the telecalling
     * queue. {@code staffId} null → unallocate (delete the sparse row). Audited via {@code profile_change_log}.
     */
    @Transactional
    public CustomerDetail assignOwner(Long customerId, Long staffId) {
        requireRole("CREDIT_HEAD", "COLLECTION_HEAD", "TELECALLER");
        // Ensure the customer exists (404 otherwise).
        detail(customerId);

        CustomerOwner existing = ownerRepository.findById(customerId).orElse(null);
        String oldVal = existing != null ? String.valueOf(existing.getOwnerStaffId()) : null;

        if (staffId == null) {
            if (existing != null) {
                ownerRepository.deleteById(customerId);
            }
            logIfChanged(customerId, null, "owner", oldVal, null);
            return detail(customerId);
        }

        StaffSummary assignee = staffDirectory.findStaff(staffId)
                .filter(StaffSummary::active)
                .orElseThrow(() -> new BusinessException("INVALID_ASSIGNEE",
                        "The assignee must be an active staff member"));

        CustomerOwner row = existing != null ? existing : new CustomerOwner();
        row.setCustomerId(customerId);
        row.setOwnerStaffId(assignee.id());
        row.setAssignedAt(Instant.now());
        ownerRepository.save(row);
        logIfChanged(customerId, null, "owner", oldVal, String.valueOf(assignee.id()));
        return detail(customerId);
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

    /** Summary of a cascade delete: how many rows went across the key tables. */
    public record DeletionResult(Long customerId, int applications, int loans, int totalRows) {
    }

    /**
     * ADMIN — permanently delete a customer and ALL of their data. Because the schema has no FK
     * constraints, this cascades by hand across every table keyed to the customer (their applications,
     * loans, verifications, documents, events, payments, collections, credentials, preferences,
     * referrals, notifications, reset tokens…), children before parents, in one transaction — so it
     * either fully succeeds or rolls back, never leaving orphans. Irreversible.
     */
    @Transactional
    public DeletionResult deleteCustomer(Long customerId) {
        requireAdmin();
        if (customerId == null) {
            throw new BusinessException("INVALID_CUSTOMER", "customerId is required");
        }
        int total = 0;
        // --- collections (uuid-keyed) → payments, all hung off this customer's loans ---
        total += jdbc.update("DELETE FROM settlement WHERE collection_case_id IN "
                + "(SELECT id FROM collection_case WHERE loan_id IN (SELECT id FROM loan WHERE customer_id = ?))", customerId);
        total += jdbc.update("DELETE FROM collection_case WHERE loan_id IN (SELECT id FROM loan WHERE customer_id = ?)", customerId);
        total += jdbc.update("DELETE FROM payment WHERE loan_id IN (SELECT id FROM loan WHERE customer_id = ?)", customerId);
        // --- application children (by the customer's application ids) ---
        total += jdbc.update("DELETE FROM application_document WHERE application_id IN (SELECT id FROM loan_application WHERE customer_id = ?)", customerId);
        total += jdbc.update("DELETE FROM application_verification WHERE application_id IN (SELECT id FROM loan_application WHERE customer_id = ?)", customerId);
        total += jdbc.update("DELETE FROM application_event WHERE application_id IN (SELECT id FROM loan_application WHERE customer_id = ?)", customerId);
        total += jdbc.update("DELETE FROM customer_profile WHERE application_id IN (SELECT id FROM loan_application WHERE customer_id = ?)", customerId);
        // --- the loan + application aggregates ---
        int loans = jdbc.update("DELETE FROM loan WHERE customer_id = ?", customerId);
        int apps = jdbc.update("DELETE FROM loan_application WHERE customer_id = ?", customerId);
        total += loans + apps;
        // --- customer-keyed satellites ---
        total += jdbc.update("DELETE FROM profile_change_log WHERE customer_id = ?", customerId);
        total += jdbc.update("DELETE FROM customer_remark WHERE customer_id = ?", customerId);
        total += jdbc.update("DELETE FROM customer_call_log WHERE customer_id = ?", customerId);
        total += jdbc.update("DELETE FROM customer_owner WHERE customer_id = ?", customerId);
        total += jdbc.update("DELETE FROM borrower_mobile WHERE customer_id = ?", customerId);
        total += jdbc.update("DELETE FROM borrower_preferences WHERE customer_id = ?", customerId);
        total += jdbc.update("DELETE FROM borrower_credential WHERE customer_id = ?", customerId);
        total += jdbc.update("DELETE FROM referral_payout WHERE beneficiary_customer_id = ? OR counterparty_customer_id = ?", customerId, customerId);
        total += jdbc.update("DELETE FROM referral WHERE referred_customer_id = ? OR referrer_customer_id = ?", customerId, customerId);
        total += jdbc.update("DELETE FROM referral_code WHERE customer_id = ?", customerId);
        total += jdbc.update("DELETE FROM income_profile WHERE customer_id = ?", customerId);
        total += jdbc.update("DELETE FROM risk_assessment WHERE customer_id = ?", customerId);
        // --- in-app inbox (delivery children first) + reset tokens ---
        total += jdbc.update("DELETE FROM notification_delivery WHERE notification_id IN "
                + "(SELECT id FROM notification WHERE recipient_type = 'BORROWER' AND recipient_id = ?)", customerId);
        total += jdbc.update("DELETE FROM notification WHERE recipient_type = 'BORROWER' AND recipient_id = ?", customerId);
        total += jdbc.update("DELETE FROM password_reset_token WHERE subject_type = 'BORROWER' AND subject_id = ?", customerId);

        if (total == 0) {
            throw new ResourceNotFoundException("Customer", "customer:" + customerId);
        }
        return new DeletionResult(customerId, apps, loans, total);
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
     * (from {@code profile_change_log}), every staff remark, and every call log — merged and sorted
     * by timestamp. Backs the "Audit Logs" tab of the customer detail.
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

        // 4. Call logs.
        for (CustomerCallLog c : callLogRepository.findByCustomerIdOrderByIdDesc(customerId)) {
            String detail = c.getCallType() + " · " + c.getOutcome()
                    + (c.getCallbackOn() != null ? " · callback " + c.getCallbackOn() : "")
                    + (c.getNotes() != null && !c.getNotes().isBlank() ? " · " + c.getNotes() : "");
            out.add(new ActivityEntry("CALL", null, "Call", detail, c.getCreatedBy(), c.getCreatedAt()));
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

    /** One customer's call logs (newest first). */
    @Transactional(readOnly = true)
    public List<CallLogView> callLogs(Long customerId) {
        return callLogRepository.findByCustomerIdOrderByIdDesc(customerId).stream()
                .map(CallLogView::of)
                .toList();
    }

    /** Add a staff call log to a customer (author + timestamp captured by JPA auditing). */
    @Transactional
    public CallLogView addCallLog(Long customerId, AddCallLogRequest req) {
        CustomerCallLog c = new CustomerCallLog();
        c.setCustomerId(customerId);
        c.setCallType(req.callType().trim());
        c.setOutcome(req.outcome().trim());
        c.setCallbackOn(req.callbackOn());
        c.setNotes(req.notes() != null ? req.notes().trim() : null);
        return CallLogView.of(callLogRepository.save(c));
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

    private String staffName(Long staffId, Map<Long, String> cache) {
        return cache.computeIfAbsent(staffId,
                id -> staffDirectory.findStaff(id).map(StaffSummary::name).orElse(null));
    }

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
        if (cs.pan() != null && cs.pan().toLowerCase().contains(needle)) {
            return true;
        }
        if (cs.mobile() != null && cs.mobile().contains(needle)) {
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

    /** ADMIN bypasses; otherwise the actor must hold one of {@code roles}. */
    private static void requireRole(String... roles) {
        CurrentActor actor = ActorContext.get();
        if (actor != null && "ADMIN".equals(actor.role())) {
            return;
        }
        if (actor == null || Arrays.stream(roles).noneMatch(r -> r.equals(actor.role()))) {
            throw new BusinessException("FORBIDDEN_ROLE",
                    "This action requires role " + String.join(" or ", roles));
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
