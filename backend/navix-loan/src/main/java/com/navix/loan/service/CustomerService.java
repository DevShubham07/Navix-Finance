package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.risk.RiskPort;
import com.navix.common.security.ActorContext;
import com.navix.common.security.BorrowerIdentityPort;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.common.verification.OtpVerifierPort;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.BureauState;
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
import com.navix.loan.entity.ApplicationReference;
import com.navix.loan.entity.ApplicationVerification;
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
import com.navix.loan.repository.ApplicationReferenceRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final BureauStateService bureauStateService;
    private final ApplicationVerificationRepository verificationRepository;
    private final ApplicationReferenceRepository referenceRepository;
    private final OtpVerifierPort otpVerifier;
    private final BorrowerIdentityPort borrowerIdentity;

    /**
     * Roles that see the ENTIRE customer book. Everyone else who holds {@code customer:view} is
     * scoped to customers they own work on (see {@link #scope()}).
     *
     * <p>Mirrored by the {@code customer:view:all} permission in the frontend {@code rbac.ts}. The
     * frontend copy only drives wording — this set is the enforcement.
     */
    private static final Set<String> FULL_CUSTOMER_VIEW_ROLES =
            Set.of("ADMIN", "CREDIT_HEAD", "COLLECTION_HEAD", "DISBURSEMENT_HEAD");

    /** IST, matching {@code ApplicationFlowService} — day boundaries are an Indian business day. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * What one scoped staffer is allowed to see. {@code null} from {@link #scope()} means "everything".
     *
     * <p>Membership is a predicate rather than a plain id set because "unallocated" cannot be
     * enumerated: a customer nobody has claimed usually has <b>no</b> {@code customer_owner} row at
     * all, so the set is defined by inversion against the customers who ARE allocated.
     */
    private record CustomerScope(Set<Long> ownedIds, Set<Long> allocatedIds) {

        /** @param allocatedIds non-null only for callers who may also see unallocated customers. */
        boolean permits(Long customerId) {
            return ownedIds.contains(customerId)
                    || (allocatedIds != null && !allocatedIds.contains(customerId));
        }
    }

    /**
     * The caller's customer scope, or {@code null} when they may see the entire book.
     *
     * <p>A scoped staffer sees a customer when they have an application <b>assigned</b> to them or
     * have <b>recorded a decision</b> on one. Roles that allocate the book (TELECALLER, via
     * {@code customer:assign}) additionally see <b>unallocated</b> customers — without that carve-out
     * a telecaller sees zero rows on {@code ?seg=unallocated} and can never claim anyone, which is
     * their entire job.
     *
     * <p>At most three queries, all constant in the number of customers — never per-customer work.
     */
    private CustomerScope scope() {
        CurrentActor actor = ActorContext.get();
        String role = actor != null ? actor.role() : null;
        if (role != null && FULL_CUSTOMER_VIEW_ROLES.contains(role)) {
            return null;
        }
        Long staffId = null;
        try {
            if (actor != null && actor.id() != null) {
                staffId = Long.valueOf(actor.id());
            }
        } catch (NumberFormatException e) {
            staffId = null;
        }
        if (staffId == null) {
            // Fail closed: an actor we cannot identify sees nothing.
            return new CustomerScope(Set.of(), null);
        }

        Set<Long> owned = new HashSet<>(
                nullSafe(applicationRepository.findCustomerIdsByAssignedExecutiveId(staffId)));

        List<Long> decidedAppIds = nullSafe(
                applicationEventRepository.findByActorIdOrderByAtDesc(String.valueOf(staffId))).stream()
                .filter(e -> DecisionHistoryService.DECISION_ACTIONS.contains(e.getAction()))
                .map(ApplicationEvent::getApplicationId)
                .distinct()
                .toList();
        if (!decidedAppIds.isEmpty()) {
            owned.addAll(nullSafe(applicationRepository.findCustomerIdsByIdIn(decidedAppIds)));
        }

        Set<Long> allocated = null;
        if ("TELECALLER".equals(role)) {
            allocated = new HashSet<>();
            for (CustomerOwner o : nullSafe(ownerRepository.findAll())) {
                if (o.getOwnerStaffId() == null) {
                    continue;
                }
                allocated.add(o.getCustomerId());
                // Customers THIS caller owns stay visible: they are allocated (so the inversion in
                // CustomerScope.permits would hide them) but claiming a lead must not make it
                // vanish from the claimer's own list.
                if (o.getOwnerStaffId().equals(staffId)) {
                    owned.add(o.getCustomerId());
                }
            }
        }
        return new CustomerScope(owned, allocated);
    }

    private static <T> Collection<T> nullSafe(Collection<T> c) {
        return c == null ? List.of() : c;
    }

    private static <T> List<T> nullSafe(List<T> c) {
        return c == null ? List.of() : c;
    }

    /** Throws 404 (never 403 — that would confirm the customer exists) when out of the caller's scope. */
    private void requireVisible(Long customerId) {
        CustomerScope scope = scope();
        if (scope != null && !scope.permits(customerId)) {
            throw new ResourceNotFoundException("Customer", String.valueOf(customerId));
        }
    }

    /** Back-compat overload — no date window. */
    @Transactional(readOnly = true)
    public List<CustomerSummary> list(String q) {
        return list(q, null, null);
    }

    /**
     * All customers (distinct customers), optionally filtered by {@code q} matching the name
     * (case-insensitive contains), PAN, mobile, or the customer id, and by an inclusive
     * {@code [from, to]} window over the customer's latest application SIGNUP date. Ordered by
     * {@code statusChangedAt} descending — when each customer's latest application entered its
     * current status — so the freshest decisions (a rejection, a sanction) surface first, even
     * though the date-window filter itself still runs on the signup date. Those two are
     * deliberately different fields: switching the window to the stage date would silently change
     * which customers appear for "Today" and diverge from the sibling live-applications/loans
     * queues, which all window on signup date.
     *
     * <p>The window is resolved in IST server-side rather than in the browser, so a staffer on a
     * UTC laptop sees the same "Today" as the live-applications queues.
     */
    @Transactional(readOnly = true)
    public List<CustomerSummary> list(String q, LocalDate from, LocalDate to) {
        rejectDsa();
        CustomerScope scope = scope();
        Instant fromInstant = from == null ? null : from.atStartOfDay(IST).toInstant();
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(IST).toInstant();
        // ponytail: whole-table rollup + client-side segmenting. Move to a paged indexed query when the
        // list stops fitting one response — same change as adding server-side segment filters.
        String needle = q != null ? q.trim().toLowerCase() : "";
        // Filter BEFORE grouping so no per-customer work (loans, outstanding, bureau state) is done
        // for a customer the caller will never be shown.
        Map<Long, List<LoanApplication>> byCustomer = applicationRepository.findAll().stream()
                .filter(a -> scope == null || scope.permits(a.getCustomerId()))
                .collect(Collectors.groupingBy(LoanApplication::getCustomerId));

        Map<Long, CustomerOwner> owners = ownerRepository.findAll().stream()
                .collect(Collectors.toMap(CustomerOwner::getCustomerId, o -> o, (a, b) -> a));
        Map<Long, String> staffNames = new HashMap<>();

        // Pass 1: resolve each customer's profile + the application id whose bureau pull should be
        // reflected (the profile's own application when present, else the newest application — so a
        // no-record pull that preceded profile creation isn't dropped to NOT_FETCHED), then batch the
        // BUREAU-state lookup in one query instead of one per customer.
        Map<Long, CustomerProfile> profileByCustomer = new HashMap<>();
        Map<Long, Long> bureauAppIdByCustomer = new HashMap<>();
        Map<Long, Long> latestAppIdByCustomerForEvents = new HashMap<>();
        for (Map.Entry<Long, List<LoanApplication>> e : byCustomer.entrySet()) {
            List<LoanApplication> apps = e.getValue();
            CustomerProfile profile = latestProfile(apps);
            profileByCustomer.put(e.getKey(), profile);
            Long latestAppId = apps.stream().max(Comparator.comparing(LoanApplication::getId))
                    .map(LoanApplication::getId).orElse(null);
            if (latestAppId != null) {
                latestAppIdByCustomerForEvents.put(e.getKey(), latestAppId);
            }
            Long bureauAppId = profile != null ? profile.getApplicationId() : latestAppId;
            if (bureauAppId != null) {
                bureauAppIdByCustomer.put(e.getKey(), bureauAppId);
            }
        }
        Map<Long, BureauState> bureauStates = bureauStateService.states(bureauAppIdByCustomer.values());
        // Stage-date rollup: when each customer's LATEST application entered its current status
        // (application_event, filtered to real transitions — see the repository javadoc). Batched
        // once for the whole list rather than per customer, and short-circuited on empty since
        // "in ()" is invalid SQL.
        Map<Long, Instant> statusChangedAtByAppId = new HashMap<>();
        if (!latestAppIdByCustomerForEvents.isEmpty()) {
            for (ApplicationEventRepository.StatusEnteredAt r : applicationEventRepository
                    .findCurrentStatusEnteredAt(latestAppIdByCustomerForEvents.values())) {
                statusChangedAtByAppId.put(r.getApplicationId(), r.getAt());
            }
        }

        LocalDate today = LocalDate.now();
        List<CustomerSummary> out = new ArrayList<>();
        for (Map.Entry<Long, List<LoanApplication>> e : byCustomer.entrySet()) {
            Long customerId = e.getKey();
            List<LoanApplication> apps = e.getValue();
            CustomerProfile profile = profileByCustomer.get(customerId);
            List<Loan> loans = loanRepository.findByCustomerId(customerId);
            long totalOutstanding = loans.stream()
                    .mapToLong(l -> repaymentService.outstandingAsOf(l.getId(), null))
                    .sum();
            // One latest application + one latest loan for the whole row, so every column below
            // describes the SAME file rather than a mix of several.
            LoanApplication latestApp = apps.stream()
                    .max(Comparator.comparing(LoanApplication::getId)).orElse(null);
            Loan latestLoan = loans.stream()
                    .max(Comparator.comparing(Loan::getId)).orElse(null);
            String latestStatus = latestApp != null ? latestApp.getStatus().name() : null;
            String loanStatus = latestLoan != null ? latestLoan.effectiveStatus(today).name() : null;
            CustomerOwner owner = owners.get(customerId);
            Long ownerStaffId = owner != null ? owner.getOwnerStaffId() : null;
            String ownerName = ownerStaffId != null ? staffName(ownerStaffId, staffNames) : null;
            Long bureauAppId = bureauAppIdByCustomer.get(customerId);
            BureauState bureauState = bureauAppId != null
                    ? bureauStates.getOrDefault(bureauAppId, BureauState.NOT_FETCHED)
                    : BureauState.NOT_FETCHED;
            Instant latestCreatedAt = latestApp != null ? latestApp.getCreatedAt() : null;
            if (!inWindow(latestCreatedAt, fromInstant, toInstant)) {
                continue;
            }
            // Where THIS advance is paid, falling back to the salary account until the borrower
            // reaches the disbursal-account step.
            String accountNumber = latestApp != null && latestApp.getDisbursalAccountNumber() != null
                    ? latestApp.getDisbursalAccountNumber()
                    : (profile != null ? profile.getSalaryAccountNumber() : null);
            String ifsc = latestApp != null && latestApp.getDisbursalIfsc() != null
                    ? latestApp.getDisbursalIfsc()
                    : (profile != null ? profile.getSalaryIfsc() : null);
            boolean amountIsRequested = latestApp != null && latestApp.getAmountRequested() != null;
            Long amountPaise = latestApp == null ? null
                    : (amountIsRequested ? latestApp.getAmountRequested() : latestApp.getEligibleLimit());
            Instant statusChangedAt = latestApp == null ? null
                    : statusChangedAtByAppId.getOrDefault(latestApp.getId(), latestApp.getCreatedAt());
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
                    ownerName,
                    bureauState,
                    latestCreatedAt,
                    latestApp != null ? latestApp.getId() : null,
                    accountNumber,
                    ifsc,
                    latestLoan != null ? latestLoan.getId() : null,
                    amountPaise,
                    amountIsRequested,
                    latestLoan != null ? latestLoan.getDueDate() : null,
                    latestApp != null ? latestApp.getMarkedPendingAt() : null,
                    statusChangedAt);
            if (matches(cs, needle)) {
                out.add(cs);
            }
        }
        // Stage date descending — when each customer's latest application entered its current status,
        // so the freshest decisions (a rejection, a sanction) surface first rather than the oldest
        // signups. Nulls last: a customer whose application predates created_at auditing has no date.
        out.sort(Comparator.comparing(CustomerSummary::statusChangedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /**
     * Inclusive {@code [from, to)} membership. A customer with no application has no date, so they
     * are excluded when a window is set and included when it is not.
     */
    private static boolean inWindow(Instant at, Instant fromInstant, Instant toInstant) {
        if (fromInstant == null && toInstant == null) {
            return true;
        }
        if (at == null) {
            return false;
        }
        return !(fromInstant != null && at.isBefore(fromInstant))
                && !(toInstant != null && !at.isBefore(toInstant));
    }

    /** A single customer's full history (newest first), or 404 if the customer has nothing on file. */
    @Transactional(readOnly = true)
    public CustomerDetail detail(Long customerId) {
        rejectDsa();
        requireVisible(customerId);
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
        // Batched (one lookup per distinct assignee, one event-table query for the whole page) —
        // same pattern as ApplicationController#enrich, feeds the "Loan applications" tab's real
        // assignee name + current-stage-entered timestamp.
        Map<Long, String> executiveNameById = new java.util.LinkedHashMap<>();
        for (Long executiveId : apps.stream().map(LoanApplication::getAssignedExecutiveId)
                .filter(Objects::nonNull).distinct().toList()) {
            executiveNameById.put(executiveId,
                    staffDirectory.findStaff(executiveId).map(StaffSummary::name).orElse(null));
        }
        List<Long> appIds = apps.stream().map(LoanApplication::getId).toList();
        Map<Long, Instant> stageEnteredAtByAppId = new java.util.LinkedHashMap<>();
        for (ApplicationEvent event : applicationEventRepository.findByApplicationIdInOrderByAtDesc(appIds)) {
            stageEnteredAtByAppId.putIfAbsent(event.getApplicationId(), event.getAt());
        }
        List<ApplicationView> appViews = apps.stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed())
                .map(a -> ApplicationView.of(a, profByApp.getOrDefault(a.getId(), profile))
                        .withAssignment(executiveNameById.get(a.getAssignedExecutiveId()),
                                stageEnteredAtByAppId.get(a.getId())))
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
        rejectDsa();
        requireVisible(customerId);
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
        CurrentActor actor = ActorContext.get();
        if (actor != null && "TELECALLER".equals(actor.role())
                && !"TELECALLER".equals(assignee.role())) {
            throw new BusinessException("FORBIDDEN_ROLE",
                    "A TELECALLER may assign customers only to another TELECALLER");
        }

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

    // ---------------------------------------------------------------- mobile-number correction (OTP)

    /**
     * ADMIN-only: send an OTP to a NEW mobile number as the first step of correcting a customer's
     * mobile on file. The code is sent to {@code newMobile} itself — there is nothing stored yet to
     * resolve server-side, so unlike every other OTP purpose in this codebase the target number
     * legitimately comes from the caller (see {@link OtpVerifierPort}'s javadoc on
     * {@link OtpVerifierPort#ADMIN_MOBILE_CHANGE}). {@link #confirmMobileChange} must be called with
     * this exact same {@code newMobile} string.
     *
     * <p>This corrects the KYC-displayed number only — {@code AuthController.claimCustomerId} never
     * reads {@code CustomerProfile.mobile}, so login identity/JWT minting is unaffected. It is
     * <b>not</b> fully inert elsewhere, though: {@code PasswordResetService.requestBorrowerReset} and
     * {@code AuthController.resolveBorrowerName} both look a profile up BY this column, so a
     * correction changes which forgot-password mobile finds this customer and what display name
     * their session shows. Two guards close the identity-collision risk that creates: the string
     * uniqueness check (mirroring {@code CustomerReviewService}'s {@code DUPLICATE_MOBILE} rule)
     * prevents two customers ever sharing one stored value, and {@link BorrowerIdentityPort} — a
     * cross-module check into {@code navix-app}'s {@code borrower_mobile} claim table — additionally
     * catches a DIFFERENT number that merely derives the same login identity (login identity keeps
     * only the last 7 digits, so two distinct 10-digit numbers can collide there even though they
     * are never string-equal).
     */
    public OtpVerifierPort.OtpRequestResult requestMobileChangeOtp(Long customerId, String newMobile) {
        requireAdmin();
        requireExistingProfile(customerId);
        String mobile = requireValidMobile(newMobile);
        requireMobileNotTaken(mobile, customerId);
        return otpVerifier.request(mobile, OtpVerifierPort.ADMIN_MOBILE_CHANGE);
    }

    /** ADMIN-only: confirm the mobile-number correction with the code sent to {@code newMobile}. */
    @Transactional
    public ProfileView confirmMobileChange(Long customerId, String newMobile, String otp) {
        requireAdmin();
        CustomerProfile profile = requireExistingProfile(customerId);
        String mobile = requireValidMobile(newMobile);
        requireMobileNotTaken(mobile, customerId);
        if (!otpVerifier.verify(mobile, otp, OtpVerifierPort.ADMIN_MOBILE_CHANGE)) {
            throw new BusinessException("INVALID_OTP", "Invalid or expired code");
        }
        logIfChanged(customerId, profile.getApplicationId(), "mobile", profile.getMobile(), mobile);
        profile.setMobile(mobile);
        return ProfileView.of(profileRepository.save(profile));
    }

    // ---------------------------------------------------------------- sanctioned-amount correction (OTP)

    /**
     * ADMIN-only: send an OTP to the customer's EXISTING mobile on file as the first step of
     * correcting the amount credit approved (sanctioned) for {@code applicationId} — this is the
     * borrower's own consent that the approved figure is changing, not merely an admin's say-so.
     * Resolving the mobile server-side (never from the request) follows the same rule every other
     * OTP purpose in this codebase follows.
     *
     * <p>The OTP purpose string is bound to {@code (customerId, applicationId, newAmountPaise)} —
     * not just the bare purpose constant — so a code requested for one figure cannot confirm a
     * different one, and a code requested for one customer's mobile cannot confirm another's.
     */
    public OtpVerifierPort.OtpRequestResult requestSanctionedAmountOtp(
            Long customerId, Long applicationId, long newAmountPaise) {
        requireAdmin();
        LoanApplication app = requireSanctionedNotYetDisbursed(customerId, applicationId);
        validateSanctionedAmount(app, newAmountPaise);
        String mobile = requireCustomerMobile(customerId, app);
        return otpVerifier.request(mobile, amountChangePurpose(customerId, app.getId(), newAmountPaise));
    }

    /** ADMIN-only: confirm the sanctioned-amount correction with the code sent to the customer's
     *  mobile on file. Does not transition the application's status — only the ceiling changes. */
    @Transactional
    public ApplicationView confirmSanctionedAmountChange(
            Long customerId, Long applicationId, long newAmountPaise, String otp) {
        requireAdmin();
        LoanApplication app = requireSanctionedNotYetDisbursed(customerId, applicationId);
        validateSanctionedAmount(app, newAmountPaise);
        String mobile = requireCustomerMobile(customerId, app);
        if (!otpVerifier.verify(mobile, otp, amountChangePurpose(customerId, app.getId(), newAmountPaise))) {
            throw new BusinessException("INVALID_OTP", "Invalid or expired code");
        }
        logIfChanged(customerId, app.getId(), "sanctionedAmountPaise",
                str(app.getSanctionedAmountPaise()), str(newAmountPaise));
        app.setSanctionedAmountPaise(newAmountPaise);
        return ApplicationView.of(applicationRepository.save(app));
    }

    /**
     * The OTP store key is {@code purpose + ":" + mobile} ({@link OtpVerifierPort} treats purpose as
     * opaque), so folding the customer, application and exact amount into the purpose string means
     * the stored code only ever confirms THAT triple — an admin cannot request a code for one figure
     * and confirm a different one, and a code cannot cross customers even if two ever shared a
     * mobile. Neither {@code BorrowerOtpService.dltTemplateKey}/{@code buildMessage} special-case
     * this purpose, so it falls through to the generic template regardless of the suffix.
     */
    private static String amountChangePurpose(Long customerId, Long applicationId, long newAmountPaise) {
        return OtpVerifierPort.ADMIN_AMOUNT_CHANGE + ":" + customerId + ":" + applicationId + ":" + newAmountPaise;
    }

    /** The customer's KYC profile, or {@code CUSTOMER_NOT_FOUND} if they have none. */
    private CustomerProfile requireExistingProfile(Long customerId) {
        CustomerProfile profile = latestProfile(applicationRepository.findByCustomerId(customerId));
        if (profile == null) {
            throw new ResourceNotFoundException("CustomerProfile", "customer:" + customerId);
        }
        return profile;
    }

    /** Bare 10-digit Indian mobile — same shape the borrower-facing OTP flows already require. */
    private static String requireValidMobile(String mobile) {
        String normalized = mobile == null ? "" : mobile.trim().replaceAll("\\D", "");
        if (!normalized.matches("[6-9]\\d{9}")) {
            throw new BusinessException("INVALID_MOBILE", "Enter a valid 10-digit mobile number");
        }
        return normalized;
    }

    /**
     * Two checks, closing two different collision shapes across the mobile-keyed lookups that trust
     * this column (forgot-password, login display name):
     * <ul>
     *   <li>string equality — mirrors {@code CustomerReviewService}'s {@code DUPLICATE_MOBILE} rule,
     *       so the exact same number can never sit on two customers' KYC profiles.</li>
     *   <li>{@link BorrowerIdentityPort} — login identity keeps only the last 7 digits, so two
     *       DIFFERENT 10-digit numbers can derive the same identity even though neither check above
     *       would ever see them as equal. This catches that case against the real claim table.</li>
     * </ul>
     */
    private void requireMobileNotTaken(String mobile, Long customerId) {
        if (profileRepository.existsMobileForOtherCustomer(mobile, customerId)) {
            throw new BusinessException("DUPLICATE_MOBILE",
                    "This mobile number is already registered with another customer.");
        }
        if (borrowerIdentity.wouldCollideWithAnotherCustomer(mobile, customerId)) {
            throw new BusinessException("MOBILE_IDENTITY_COLLISION",
                    "This mobile number's login identity is already claimed by another customer.");
        }
    }

    /**
     * The specific application a correction targets, verified to actually be the customer's own and
     * to actually be sanctioned but not yet disbursed. Once {@code loanId} is set,
     * {@link Loan#getPrincipal()} is the immutable, actually-disbursed figure and nothing here may
     * touch it; a {@code CANCELLED}/{@code REJECTED} application that happens to retain a stale
     * {@code sanctionedAmountPaise} is excluded by the explicit status check.
     */
    private LoanApplication requireSanctionedNotYetDisbursed(Long customerId, Long applicationId) {
        if (applicationId == null) {
            throw new BusinessException("APPLICATION_ID_REQUIRED", "applicationId is required");
        }
        return applicationRepository.findByCustomerId(customerId).stream()
                .filter(a -> a.getId().equals(applicationId))
                .filter(a -> a.getStatus() == ApplicationStatus.SANCTIONED)
                .filter(a -> a.getLoanId() == null && a.getSanctionedAmountPaise() != null)
                .findFirst()
                .orElseThrow(() -> new BusinessException("NO_SANCTIONED_APPLICATION",
                        "This application is not sanctioned and awaiting disbursement"));
    }

    /** Mirrors {@code sanction()}'s own floor; also protects a draw amount the borrower already chose. */
    private static void validateSanctionedAmount(LoanApplication app, long newAmountPaise) {
        if (newAmountPaise < LoanMath.MIN_LOAN_PAISE) {
            throw new BusinessException("AMOUNT_TOO_LOW", "The sanctioned amount is below the minimum of ₹1,000");
        }
        Long drawn = app.getAmountRequested();
        if (drawn != null && newAmountPaise < drawn) {
            throw new BusinessException("BELOW_DRAWN_AMOUNT",
                    "The borrower already chose to draw " + (drawn / 100) + " — the sanctioned amount "
                            + "cannot be corrected below that");
        }
    }

    private String requireCustomerMobile(Long customerId, LoanApplication app) {
        CustomerProfile profile = profileRepository.findByApplicationId(app.getId())
                .orElseGet(() -> requireExistingProfile(customerId));
        if (profile.getMobile() == null || profile.getMobile().isBlank()) {
            throw new BusinessException("MOBILE_MISSING", "This customer has no mobile number on file");
        }
        return profile.getMobile();
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
     * (from {@code profile_change_log}), every uploaded document (from {@code application_document},
     * metadata only), every staff remark, and every call log — merged and sorted
     * by timestamp. Backs the "Audit Logs" tab of the customer detail.
     */
    @Transactional(readOnly = true)
    public List<ActivityEntry> activity(Long customerId) {
        rejectDsa();
        requireVisible(customerId);
        List<ActivityEntry> out = new ArrayList<>();

        // 1. Lifecycle + re-verify events, verification steps, and reference contacts across every
        //    application this customer owns.
        List<LoanApplication> apps = applicationRepository.findByCustomerId(customerId);
        for (LoanApplication a : apps) {
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

            // 1a. Verification steps (PAN/EMAIL/ADDRESS/DIGILOCKER/AADHAAR/BUREAU/SALARY/PENNY_DROP/
            //     SELFIE/AGREEMENT) — no checkType allowlist, every row falls out via humanize().
            for (ApplicationVerification v : verificationRepository.findByApplicationIdOrderByIdAsc(a.getId())) {
                List<String> parts = new ArrayList<>();
                if (v.getStatus() != null) {
                    parts.add(v.getStatus());
                }
                if (v.getProvider() != null) {
                    parts.add(v.getProvider());
                }
                if (v.getNameMatch() != null) {
                    parts.add("name match " + Math.round(v.getNameMatch() * 100) + "%");
                }
                if (v.getScore() != null) {
                    parts.add("score " + v.getScore());
                }
                if (v.getMessage() != null && !v.getMessage().isBlank()) {
                    parts.add(v.getMessage());
                }
                out.add(new ActivityEntry(
                        "VERIFICATION",
                        a.getId(),
                        humanize(v.getCheckType()) + " check",
                        parts.isEmpty() ? null : String.join(" · ", parts),
                        v.getCreatedBy(),
                        v.getUpdatedAt() != null ? v.getUpdatedAt() : v.getCreatedAt()));
            }

            // 1b. The two reference contacts named on the Phase-3 references screen.
            for (ApplicationReference r : referenceRepository.findByApplicationIdOrderBySlotAsc(a.getId())) {
                out.add(new ActivityEntry(
                        "REFERENCE",
                        a.getId(),
                        "Reference " + r.getSlot(),
                        r.getFullName() + " · " + r.getMobile() + " · " + humanize(r.getRelation()),
                        r.getCreatedBy(),
                        r.getUpdatedAt() != null ? r.getUpdatedAt() : r.getCreatedAt()));
            }
        }

        // 1c. Document uploads across every application — one batch query (same aggregation the
        //     Documents tab uses), never a per-application loop. Deliberately metadata only: the S3
        //     object key, any presigned URL and the borrower's file password are sensitive and must
        //     not leak into this staff-visible read-only feed.
        if (!apps.isEmpty()) {
            List<Long> appIds = apps.stream().map(LoanApplication::getId).toList();
            for (ApplicationDocument d : documentRepository
                    .findByApplicationIdInOrderByApplicationIdDescIdAsc(appIds)) {
                List<String> parts = new ArrayList<>();
                if (d.getFileName() != null && !d.getFileName().isBlank()) {
                    parts.add(d.getFileName());
                }
                if (d.getContentType() != null && !d.getContentType().isBlank()) {
                    parts.add(d.getContentType());
                }
                if (d.getSizeBytes() != null) {
                    parts.add(d.getSizeBytes() + " bytes");
                }
                out.add(new ActivityEntry(
                        "UPLOAD",
                        d.getApplicationId(),
                        humanize(d.getDocType()) + " uploaded",
                        parts.isEmpty() ? null : String.join(" · ", parts),
                        d.getCreatedBy(),
                        d.getUpdatedAt() != null ? d.getUpdatedAt() : d.getCreatedAt()));
            }
        }

        // 2. Profile / salary edits (carry the new value + who + when). A null old value is an
        //    initial entry (the borrower first typing the field in onboarding), not an edit.
        for (ProfileChangeLog c : changeLogRepository.findByCustomerIdOrderByIdDesc(customerId)) {
            boolean initial = c.getOldValue() == null;
            String newVal = c.getNewValue() != null ? c.getNewValue() : "—";
            out.add(new ActivityEntry(
                    "PROFILE",
                    c.getApplicationId(),
                    (initial ? "Entered " : "Updated ") + humanize(c.getField()),
                    initial ? newVal : c.getOldValue() + " → " + newVal,
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
        rejectDsa();
        requireVisible(customerId);
        return remarkRepository.findByCustomerIdOrderByIdDesc(customerId).stream()
                .map(RemarkView::of)
                .toList();
    }

    /** Add a staff remark to a customer (author + timestamp captured by JPA auditing). */
    @Transactional
    public RemarkView addRemark(Long customerId, String body) {
        rejectDsa();
        requireVisible(customerId);
        CustomerRemark r = new CustomerRemark();
        r.setCustomerId(customerId);
        r.setBody(body.trim());
        return RemarkView.of(remarkRepository.save(r));
    }

    /**
     * One customer's call logs (newest first), optionally narrowed to a single loan via
     * {@code loanId} — the per-loan call history a customer-level log alone can't give.
     */
    @Transactional(readOnly = true)
    public List<CallLogView> callLogs(Long customerId, Long loanId) {
        rejectDsa();
        requireVisible(customerId);
        List<CustomerCallLog> rows = loanId != null
                ? callLogRepository.findByCustomerIdAndLoanIdOrderByIdDesc(customerId, loanId)
                : callLogRepository.findByCustomerIdOrderByIdDesc(customerId);
        return rows.stream().map(CallLogView::of).toList();
    }

    /** Add a staff call log to a customer (author + timestamp captured by JPA auditing). */
    @Transactional
    public CallLogView addCallLog(Long customerId, AddCallLogRequest req) {
        rejectDsa();
        requireVisible(customerId);
        if (req.loanId() != null) {
            Loan loan = loanRepository.findById(req.loanId())
                    .orElseThrow(() -> new BusinessException("LOAN_NOT_FOUND",
                            "No such loan: " + req.loanId()));
            if (!customerId.equals(loan.getCustomerId())) {
                throw new BusinessException("LOAN_CUSTOMER_MISMATCH",
                        "That loan does not belong to this customer");
            }
        }
        CustomerCallLog c = new CustomerCallLog();
        c.setCustomerId(customerId);
        c.setLoanId(req.loanId());
        c.setCallType(req.callType().trim());
        c.setOutcome(req.outcome().trim());
        c.setCallbackOn(req.callbackOn());
        c.setNotes(req.notes() != null ? req.notes().trim() : null);
        c.setCreatedByStaffId(actorStaffIdOrNull());
        return CallLogView.of(callLogRepository.save(c));
    }

    /** The acting staff id, or null when it isn't resolvable (system paths) — never a guess. */
    private Long actorStaffIdOrNull() {
        try {
            return Long.valueOf(ActorContext.get().id());
        } catch (RuntimeException e) {
            return null;
        }
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

    /**
     * DSAs are firewalled from all customer data (spec: "no customer data, no pipeline") even though
     * every other staff role holds the broad {@code customer:view} permission — so this is a
     * deliberate exclusion, not a role allowlist. Called at the top of the customer-PII read paths.
     */
    private static void rejectDsa() {
        CurrentActor actor = ActorContext.get();
        if (actor != null && "DSA".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "DSAs cannot view customer data");
        }
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
