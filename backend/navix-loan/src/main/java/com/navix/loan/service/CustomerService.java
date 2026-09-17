package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.notification.event.LoanLimitRevisedEvent;
import com.navix.common.notification.event.SanctionedAmountRevisedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.BorrowerIdentityPort;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.common.verification.OtpVerifierPort;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.BureauState;
import com.navix.loan.dto.CustomerDtos.CaseFailureDetail;
import com.navix.loan.dto.CustomerDtos.ProviderAttemptView;
import com.navix.loan.dto.CustomerDtos.ActivityEntry;
import com.navix.loan.dto.CustomerDtos.AddCallLogRequest;
import com.navix.loan.dto.CustomerDtos.ApplicationDocumentGroup;
import com.navix.loan.dto.CustomerDtos.BookStats;
import com.navix.loan.dto.CustomerDtos.CallLogView;
import com.navix.loan.dto.CustomerDtos.DpdBuckets;
import com.navix.loan.dto.CreditBriefDtos.CreditBriefView;
import com.navix.loan.dto.CustomerDtos.CustomerDetail;
import com.navix.loan.dto.CustomerDtos.CustomerPage;
import com.navix.loan.dto.CustomerDtos.CustomerSummary;
import com.navix.loan.dto.CustomerDtos.CustomerSummaryCounts;
import com.navix.loan.dto.CustomerDtos.ProfileChangeView;
import com.navix.loan.dto.CustomerDtos.RemarkView;
import com.navix.loan.dto.CustomerDtos.UpdateCustomerRequest;
import com.navix.loan.dto.LoanDtos;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.dto.LoanDtos.PaymentView;
import com.navix.loan.dto.ReviewDtos.DocumentView;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.ApplicationReference;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.CustomerCallLog;
import com.navix.loan.entity.CustomerLimitOverride;
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
import com.navix.loan.repository.CustomerLimitOverrideRepository;
import com.navix.loan.repository.CustomerOwnerRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.CustomerRemarkRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.repository.ProfileChangeLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
    private final CustomerLimitOverrideRepository limitOverrideRepository;
    private final EligibilityService eligibilityService;
    private final CustomerCallLogRepository callLogRepository;
    private final StaffDirectory staffDirectory;
    private final com.navix.common.loan.ApplicationActorDirectory applicationActorDirectory;
    private final com.navix.common.collections.CollectionCaseDirectory collectionCaseDirectory;
    private final JdbcTemplate jdbc;
    private final CreditBriefService creditBriefService;
    private final ApplicationDocumentRepository documentRepository;
    private final BureauStateService bureauStateService;
    private final VerificationFailureService verificationFailureService;
    private final com.navix.common.verification.ProviderAttemptDirectory providerAttempts;
    private final ApplicationVerificationRepository verificationRepository;
    private final ApplicationReferenceRepository referenceRepository;
    private final OtpVerifierPort otpVerifier;
    private final BorrowerIdentityPort borrowerIdentity;
    private final ApplicationEventPublisher eventPublisher;
    private final LoanMath loanMath;
    private final CustomerBookQuery bookQuery;

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
        owned.addAll(decidedCustomerIds(staffId));

        // A collections officer acquires neither of the two above: the credit assignment is a
        // different field (assigned_executive_id) and DECISION_ACTIONS are all credit/disbursement
        // lifecycle actions, so logging a call, an interaction or a payment never earns visibility.
        // Their book is the collection cases assigned to them, which lives in navix-collections and
        // is reached through the port rather than a cross-module repository.
        Collection<Long> collectionLoanIds =
                nullSafe(collectionCaseDirectory.loanIdsAssignedTo(staffId));
        if (!collectionLoanIds.isEmpty()) {
            owned.addAll(nullSafe(loanRepository.findCustomerIdsByIdIn(collectionLoanIds)));
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

    /**
     * Customers on whose applications this staffer has recorded a decision (the
     * {@link DecisionHistoryService#DECISION_ACTIONS} trail) — shared by the visibility scope and by
     * the "My customers" filter so both mean exactly the same thing as the dashboard's "your book".
     */
    private Set<Long> decidedCustomerIds(Long staffId) {
        List<Long> decidedAppIds = nullSafe(
                applicationEventRepository.findByActorIdOrderByAtDesc(String.valueOf(staffId))).stream()
                .filter(e -> DecisionHistoryService.DECISION_ACTIONS.contains(e.getAction()))
                .map(ApplicationEvent::getApplicationId)
                .distinct()
                .toList();
        if (decidedAppIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(nullSafe(applicationRepository.findCustomerIdsByIdIn(decidedAppIds)));
    }

    /**
     * The acting staff id, or null when the actor cannot be identified as one — the resolution
     * {@link #mineCustomerIds()} and {@link #bookStats()} share, so "my book" and "allocated to me"
     * can never disagree about who "me" is.
     */
    private static Long actorStaffId() {
        CurrentActor actor = ActorContext.get();
        try {
            return actor != null && actor.id() != null ? Long.valueOf(actor.id()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Owned by the caller OR decided by the caller — the frontend's {@code isMine} rule, server-side. */
    private Set<Long> mineCustomerIds() {
        Long staffId = actorStaffId();
        if (staffId == null) {
            return Set.of();
        }
        Set<Long> mine = new HashSet<>(nullSafe(ownerRepository.findCustomerIdsByOwnerStaffId(staffId)));
        mine.addAll(decidedCustomerIds(staffId));
        return mine;
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

    /** Hard ceiling on one page of the Customers list. */
    public static final int MAX_PAGE_SIZE = 100;
    /** Hard ceiling on a "download all customers" export. */
    public static final int EXPORT_CAP = 50_000;

    /**
     * One page of the Customers list. Filtering (search, IST date window, segment, scope, "mine"),
     * sorting and paging all happen in SQL ({@link CustomerBookQuery}); only the ids on the page are
     * hydrated into full rows, so the cost is bounded by {@code size}, not by the size of the book.
     */
    @Transactional(readOnly = true)
    public CustomerPage page(String q, LocalDate from, LocalDate to, String seg, boolean mine,
            int page, int size) {
        rejectDsa();
        CustomerBookQuery.BookFilter filter = bookFilter(q, from, to, seg, mine);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(1, page);
        long total = bookQuery.count(filter);
        List<Long> ids = bookQuery.pageIds(filter, (safePage - 1) * safeSize, safeSize);
        return new CustomerPage(hydrate(ids), safePage, safeSize, total);
    }

    /** Every matching row under the same filter as {@link #page} — the ADMIN "download all" export. */
    @Transactional(readOnly = true)
    public List<CustomerSummary> export(String q, LocalDate from, LocalDate to, String seg, boolean mine) {
        requireAdmin();
        CustomerBookQuery.BookFilter filter = bookFilter(q, from, to, seg, mine);
        return hydrate(bookQuery.pageIds(filter, 0, EXPORT_CAP));
    }

    /**
     * The batched twin of {@link #detail}'s summary row: full rows for exactly these customers, in
     * the order the ids were given. This is what the collections export's enrichment and the
     * dashboard's decided-customer join use <b>instead of loading the whole book</b> and picking a
     * handful of rows out of it in the browser.
     *
     * <p>Capped at {@link #MAX_PAGE_SIZE} ids (a batch lookup, not a second list endpoint) and
     * de-duplicated first, so asking for the same id twice does not eat the budget. Ids outside the
     * caller's {@link #scope()} — and ids that simply do not exist — are silently omitted rather
     * than refused: a 403/404 here would confirm that a customer exists, which is exactly the
     * enumeration oracle {@link #requireVisible} avoids.
     */
    @Transactional(readOnly = true)
    public List<CustomerSummary> byIds(List<Long> ids) {
        rejectDsa();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        CustomerScope scope = scope();
        List<Long> kept = ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_PAGE_SIZE)
                .filter(id -> scope == null || scope.permits(id))
                .toList();
        return hydrate(kept);
    }

    /**
     * The caller's own book, aggregated server-side: lifecycle counts, exposure, DPD bands and the
     * headline averages the staff dashboard shows. Replaces shipping every customer row to the
     * browser so it can count them there.
     *
     * <p>"Own book" is {@link #mineCustomerIds()} — owned by OR decided by the caller, the same rule
     * the {@code mine} filter on {@link #page} applies — and the arithmetic is a port of the
     * frontend's {@code lib/staff/my-stats.ts} {@code bookStats}, so the two agree figure for
     * figure. Its rule for an unmeasurable value holds here too: a metric with no denominator is
     * {@code null}, never {@code 0}.
     */
    @Transactional(readOnly = true)
    public BookStats bookStats() {
        rejectDsa();
        Long staffId = actorStaffId();
        List<Long> mine = new ArrayList<>(mineCustomerIds());
        // hydrate() is bounded by the IN (...) list it builds, so feed it a page at a time rather
        // than handing a whole book to one query.
        List<CustomerSummary> rows = new ArrayList<>();
        for (int i = 0; i < mine.size(); i += MAX_PAGE_SIZE) {
            rows.addAll(hydrate(mine.subList(i, Math.min(i + MAX_PAGE_SIZE, mine.size()))));
        }

        long outstandingPaise = 0;
        long atRiskPaise = 0;
        long d1to30 = 0;
        long d31to60 = 0;
        long d60plus = 0;
        long dueNext7Days = 0;
        long largestExposurePaise = 0;
        long repeatBorrowers = 0;
        long thinFile = 0;
        long toChase = 0;
        long ownedCount = 0;
        long ownedOverdue = 0;
        long ticketSum = 0;
        long ticketCount = 0;
        long scoreSum = 0;
        long scoreCount = 0;
        LocalDate today = LocalDate.now(IST);

        for (CustomerSummary c : rows) {
            outstandingPaise += c.totalOutstandingPaise();
            if ("overdue".equals(CustomerSegments.segmentOf(c.loanStatus(), c.latestStatus()))) {
                atRiskPaise += c.totalOutstandingPaise();
            }
            if (c.loanDueDate() != null) {
                // The same signed whole-day difference the frontend's daysBetween computes:
                // positive = days late, negative = days still to run.
                long daysLate = ChronoUnit.DAYS.between(c.loanDueDate(), today);
                if (daysLate >= 1 && daysLate <= 30) {
                    d1to30++;
                } else if (daysLate >= 31 && daysLate <= 60) {
                    d31to60++;
                } else if (daysLate > 60) {
                    d60plus++;
                }
                if (daysLate >= -7 && daysLate < 0) {
                    dueNext7Days++;
                }
            }
            if (c.amountIsRequested() && c.amountPaise() != null) {
                ticketSum += c.amountPaise();
                ticketCount++;
            }
            if (c.creditScore() != null) {
                scoreSum += c.creditScore();
                scoreCount++;
            }
            largestExposurePaise = Math.max(largestExposurePaise, c.totalOutstandingPaise());
            if (c.loanCount() > 1) {
                repeatBorrowers++;
            }
            if (c.bureauState() == BureauState.NO_RECORD) {
                thinFile++;
            }
            if ("DRAFT".equals(c.latestStatus())) {
                toChase++;
            }
            if (staffId != null && staffId.equals(c.ownerStaffId())) {
                ownedCount++;
                if ("OVERDUE".equals(c.loanStatus()) || "IN_COLLECTIONS".equals(c.loanStatus())) {
                    ownedOverdue++;
                }
            }
        }

        return new BookStats(
                rows.size(),
                CustomerSegments.counts(rows),
                outstandingPaise,
                atRiskPaise,
                new DpdBuckets(d1to30, d31to60, d60plus),
                dueNext7Days,
                ticketCount > 0 ? (double) ticketSum / ticketCount : null,
                largestExposurePaise,
                outstandingPaise > 0 ? (double) largestExposurePaise / outstandingPaise : null,
                repeatBorrowers,
                scoreCount > 0 ? (double) scoreSum / scoreCount : null,
                thinFile,
                toChase,
                ownedCount,
                ownedOverdue);
    }

    /** The segment-chip counts under the same search / window / scope / "mine" as {@link #page}. */
    @Transactional(readOnly = true)
    public CustomerSummaryCounts summary(String q, LocalDate from, LocalDate to, boolean mine) {
        rejectDsa();
        CustomerBookQuery.BookFilter filter = bookFilter(q, from, to, null, mine);
        Map<String, Long> bySegment = new HashMap<>();
        long all = 0;
        long unallocated = 0;
        for (CustomerBookQuery.SegmentCount c : bookQuery.segmentCounts(filter)) {
            bySegment.merge(c.segment(), c.count(), Long::sum);
            all += c.count();
            unallocated += c.unallocated();
        }
        return new CustomerSummaryCounts(
                all,
                bySegment.getOrDefault("incomplete", 0L),
                bySegment.getOrDefault("pending", 0L),
                bySegment.getOrDefault("review", 0L),
                bySegment.getOrDefault("approved", 0L),
                bySegment.getOrDefault("disbursementPending", 0L),
                bySegment.getOrDefault("active", 0L),
                bySegment.getOrDefault("overdue", 0L),
                bySegment.getOrDefault("hold", 0L),
                bySegment.getOrDefault("rejected", 0L),
                bySegment.getOrDefault("closed", 0L),
                unallocated);
    }

    private CustomerBookQuery.BookFilter bookFilter(String q, LocalDate from, LocalDate to, String seg,
            boolean mine) {
        CustomerScope scope = scope();
        String needle = q != null ? q.trim().toLowerCase() : "";
        Instant fromInstant = from == null ? null : from.atStartOfDay(IST).toInstant();
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(IST).toInstant();
        String segment = seg != null && CustomerBookQuery.SEGMENTS.contains(seg) ? seg : null;
        return new CustomerBookQuery.BookFilter(
                needle,
                fromInstant,
                toInstant,
                segment,
                scope == null ? null : scope.ownedIds(),
                scope != null && scope.allocatedIds() != null,
                mine ? mineCustomerIds() : null,
                LocalDate.now(IST));
    }

    /** Full rows for exactly these customers, returned in the order the ids were given. */
    private List<CustomerSummary> hydrate(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, List<LoanApplication>> byCustomer = nullSafe(applicationRepository.findByCustomerIdIn(ids))
                .stream().collect(Collectors.groupingBy(LoanApplication::getCustomerId));
        List<Loan> loans = nullSafe(loanRepository.findByCustomerIdIn(ids)).stream()
                .filter(l -> l.getCustomerId() != null)
                .toList();
        Map<Long, CustomerOwner> owners = nullSafe(ownerRepository.findAllById(ids)).stream()
                .collect(Collectors.toMap(CustomerOwner::getCustomerId, o -> o, (a, b) -> a));
        Map<Long, CustomerSummary> byId = new HashMap<>();
        for (CustomerSummary cs : buildRows(byCustomer, loans, owners)) {
            byId.put(cs.customerId(), cs);
        }
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /**
     * Builds the full {@link CustomerSummary} rows for the customers in {@code byCustomer}. Every
     * lookup is batched over the whole input (never per customer), so the caller decides the cost —
     * always one page's (or one batch's) ids from {@link #hydrate}. Unsorted, and unfiltered: search
     * and the date window are resolved in SQL by {@link CustomerBookQuery} before an id ever gets
     * here.
     */
    private List<CustomerSummary> buildRows(Map<Long, List<LoanApplication>> byCustomer, List<Loan> allLoans,
            Map<Long, CustomerOwner> owners) {
        Map<Long, List<Loan>> loansByCustomer = allLoans.stream()
                .collect(Collectors.groupingBy(Loan::getCustomerId));
        Map<Long, Long> owedByLoanId = repaymentService.outstandingForAll(allLoans, null);
        Map<Long, String> staffNames = new HashMap<>();

        // Every profile these applications could resolve to, batched in one query instead of one
        // findByApplicationId per application — latestProfile(apps, profileByAppId) below just picks
        // among them.
        List<Long> allAppIds = byCustomer.values().stream().flatMap(List::stream)
                .map(LoanApplication::getId).toList();
        Map<Long, CustomerProfile> profileByAppId = allAppIds.isEmpty() ? new HashMap<>()
                : profileRepository.findByApplicationIdIn(allAppIds).stream()
                    .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));

        // Pass 1: resolve each customer's profile + the application id whose bureau pull should be
        // reflected (the profile's own application when present, else the newest application — so a
        // no-record pull that preceded profile creation isn't dropped to NOT_FETCHED), then batch the
        // BUREAU-state lookup in one query instead of one per customer.
        Map<Long, CustomerProfile> profileByCustomer = new HashMap<>();
        Map<Long, Long> bureauAppIdByCustomer = new HashMap<>();
        Map<Long, Long> latestAppIdByCustomerForEvents = new HashMap<>();
        for (Map.Entry<Long, List<LoanApplication>> e : byCustomer.entrySet()) {
            List<LoanApplication> apps = e.getValue();
            CustomerProfile profile = latestProfile(apps, profileByAppId);
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
        // Same application ids the bureau state is read from, so the Failure column always
        // describes the SAME file the Bureau column does.
        Map<Long, VerificationFailureService.CaseFailure> failures =
                verificationFailureService.failures(bureauAppIdByCustomer.values());
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

        // Who handled each customer's latest file, batched over the same application ids the stage
        // dates were resolved from, plus the collections officer keyed by loan.
        Map<Long, com.navix.common.loan.ApplicationActorDirectory.HandledBy> handledByApp =
                applicationActorDirectory.byApplicationId(latestAppIdByCustomerForEvents.values());
        Map<Long, String> collectionOfficerNameByLoanId = collectionOfficerNames(allLoans);

        LocalDate today = LocalDate.now();
        List<CustomerSummary> out = new ArrayList<>();
        for (Map.Entry<Long, List<LoanApplication>> e : byCustomer.entrySet()) {
            Long customerId = e.getKey();
            List<LoanApplication> apps = e.getValue();
            CustomerProfile profile = profileByCustomer.get(customerId);
            List<Loan> loans = loansByCustomer.getOrDefault(customerId, List.of());
            long totalOutstanding = loans.stream()
                    .mapToLong(l -> owedByLoanId.getOrDefault(l.getId(), 0L))
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
            VerificationFailureService.CaseFailure failure = bureauAppId != null
                    ? failures.getOrDefault(bureauAppId, VerificationFailureService.CaseFailure.none())
                    : VerificationFailureService.CaseFailure.none();
            Instant latestCreatedAt = latestApp != null ? latestApp.getCreatedAt() : null;
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
            var handled = latestApp == null
                    ? com.navix.common.loan.ApplicationActorDirectory.HandledBy.NONE
                    : handledByApp.getOrDefault(latestApp.getId(),
                            com.navix.common.loan.ApplicationActorDirectory.HandledBy.NONE);
            String collectionOfficerName = latestLoan == null ? null
                    : collectionOfficerNameByLoanId.get(latestLoan.getId());
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
                    profile != null ? profile.getBureauSource() : null,
                    latestCreatedAt,
                    latestApp != null ? latestApp.getId() : null,
                    accountNumber,
                    ifsc,
                    latestLoan != null ? latestLoan.getId() : null,
                    amountPaise,
                    amountIsRequested,
                    latestLoan != null ? latestLoan.getDueDate() : null,
                    latestApp != null ? latestApp.getMarkedPendingAt() : null,
                    statusChangedAt,
                    handled.creditDecidedByName(),
                    handled.disbursedByName(),
                    collectionOfficerName,
                    latestApp != null ? latestApp.getSalaryCreditDay() : null,
                    failure.reason().name(),
                    failure.reason().severity().name(),
                    failure.reason().retryable());
            out.add(cs);
        }
        return out;
    }

    /**
     * Loan id → assigned collections officer's name, for every loan passed in. One query across the
     * whole page via the collections seam, then one name lookup per distinct officer.
     */
    private Map<Long, String> collectionOfficerNames(Collection<Loan> loans) {
        List<Long> loanIds = loans.stream().map(Loan::getId).toList();
        if (loanIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> officerIdByLoanId = collectionCaseDirectory.assignedOfficerByLoanId(loanIds);
        Map<Long, String> nameByStaffId = new HashMap<>();
        Map<Long, String> result = new HashMap<>();
        officerIdByLoanId.forEach((loanId, staffId) -> {
            if (staffId == null) {
                return;
            }
            result.put(loanId, nameByStaffId.computeIfAbsent(staffId,
                    id -> staffDirectory.findStaff(id).map(StaffSummary::name).orElse(null)));
        });
        return result;
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
        // One batched pass for the whole customer: the per-loan call costs 3 queries each (loan
        // reload + verified-payment sum + settlement lookup), so a returning borrower's 6 loans cost
        // 18 round trips to say what two queries can. Same formula either way — it is literally the
        // same private helper inside RepaymentService, so the figures cannot fork.
        Map<Long, RepaymentService.OutstandingBreakdown> breakdowns =
                repaymentService.outstandingBreakdownsForAll(loans, null);
        List<LoanView> loanViews = loans.stream()
                .sorted(Comparator.comparing(Loan::getId).reversed())
                .map(l -> LoanView.of(l, breakdowns.get(l.getId()).outstandingPaise(),
                        l.effectiveStatus(today)))
                .toList();
        // The itemised make-up of those same balances, so the Loans tab can show the interest /
        // penalty working per loan card without one /outstanding call each.
        LocalDate asOf = LocalDate.now(IST);
        Map<Long, LoanDtos.OutstandingView> outstandingByLoanId = new java.util.LinkedHashMap<>();
        breakdowns.forEach((loanId, b) -> outstandingByLoanId.put(loanId,
                new LoanDtos.OutstandingView(loanId, asOf, b.outstandingPaise(),
                        b.settledAmountPaise(), b.interestPaise(), b.penaltyPaise(),
                        b.verifiedPaise(), b.interestDays(), b.penaltyDays())));

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
                ownerStaffId, ownerName, creditBrief,
                eligibilityService.overrideOf(customerId).orElse(null),
                outstandingByLoanId);
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

        // PATCH, not replace — see the field's javadoc. Only touched when a value is actually sent.
        if (req.dob() != null) {
            LocalDate dob = requirePlausibleDob(req.dob());
            logIfChanged(customerId, appId, "dob", str(profile.getDob()), str(dob));
            profile.setDob(dob);
        }

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
            // Honours an ADMIN limit override, which outranks the salary rule (V69).
            eligibilityService.recomputeForCustomer(customerId, saved.getMonthlySalaryPaise());
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

    // ---------------------------------------------------------------- sanctioned-amount correction

    /**
     * ADMIN-only: correct the amount credit approved (sanctioned) for {@code applicationId} —
     * verified server-side to be this customer's own, sanctioned, not-yet-disbursed application.
     * Takes effect immediately; there is no borrower-side consent step. The change is audited to
     * {@code profile_change_log} like every other admin correction, and the borrower is notified
     * (email + in-app) once the transaction commits — see {@code SanctionedAmountRevisedEvent}. Does
     * not transition the application's status — only the ceiling changes.
     */
    @Transactional
    public ApplicationView changeSanctionedAmount(Long customerId, Long applicationId, long newAmountPaise) {
        requireAdmin();
        LoanApplication app = requireSanctionedNotYetDisbursed(customerId, applicationId);
        validateSanctionedAmount(app, newAmountPaise);
        long previousAmountPaise = app.getSanctionedAmountPaise();
        logIfChanged(customerId, app.getId(), "sanctionedAmountPaise",
                str(previousAmountPaise), str(newAmountPaise));
        app.setSanctionedAmountPaise(newAmountPaise);
        ApplicationView view = ApplicationView.of(applicationRepository.save(app));
        eventPublisher.publishEvent(new SanctionedAmountRevisedEvent(
                customerId, app.getId(), previousAmountPaise, newAmountPaise, Instant.now()));
        return view;
    }

    // ---------------------------------------------------------------- salary-credit-day correction

    /**
     * ADMIN-only: correct the salary-credit day (1–31) on the customer's <b>latest</b> application —
     * the one {@code ApplicationFlowService.latestSalaryCreditDay} reads for the next reborrow, and
     * the one {@code LoanService.disburse} reads at disbursal to compute {@code loan.due_date}.
     *
     * <p>When that application is still {@code SANCTIONED} with no loan yet ({@code loanId == null}),
     * the borrower has a pending offer whose repayment date was projected from the OLD day — so this
     * also recomputes {@code approvedRepaymentDate}/{@code sanctionTenureDays} with the same
     * {@link LoanMath#dueDateFromSalary} formula {@code sanction()}/{@code carryOverForReapply()} use
     * (evaluated in IST here, unlike those two, which is out of scope to change). A
     * {@code DISBURSEMENT_PENDING} application is deliberately left alone — the borrower already
     * eSigned the Key Fact Statement carrying that date — and a disbursed loan's stored
     * {@code due_date} is never touched; only the stored day changes for those.
     */
    @Transactional
    public ApplicationView changeSalaryCreditDay(Long customerId, int day) {
        requireAdmin();
        if (day < 1 || day > 31) {
            throw new BusinessException("INVALID_SALARY_DAY", "Salary credit day must be between 1 and 31");
        }
        LoanApplication app = applicationRepository.findByCustomerId(customerId).stream()
                .max(Comparator.comparing(LoanApplication::getId))
                .orElseThrow(() -> new BusinessException("NO_APPLICATION", "This customer has no application"));
        logIfChanged(customerId, app.getId(), "salaryCreditDay", str(app.getSalaryCreditDay()), str(day));
        app.setSalaryCreditDay(day);
        if (app.getStatus() == ApplicationStatus.SANCTIONED && app.getLoanId() == null) {
            LocalDate today = LocalDate.now(IST);
            LocalDate due = loanMath.dueDateFromSalary(today, day);
            logIfChanged(customerId, app.getId(), "approvedRepaymentDate",
                    str(app.getApprovedRepaymentDate()), due.toString());
            app.setApprovedRepaymentDate(due);
            app.setSanctionTenureDays((int) ChronoUnit.DAYS.between(today, due));
        }
        return ApplicationView.of(applicationRepository.save(app));
    }

    // ---------------------------------------------------------------- eligible-limit override

    /**
     * ADMIN-only: set (or clear) this customer's eligible limit, overriding the 25%-of-salary rule.
     *
     * <p>Stored per customer rather than per application because the limit is re-derived from salary
     * on payslip verification, on a salary edit and on every reborrow — so an edit to a single
     * application's {@code eligible_limit} would be silently overwritten. {@code newLimitPaise} of
     * null clears the override and hands the customer back to the salary rule. There is no upper
     * ceiling (an admin may exceed the ₹10,00,000 instant-loan cap the formula applies); the floor is
     * the usual ₹1,000 minimum loan. Audited to {@code profile_change_log} like every other admin
     * correction, and applied immediately to every not-yet-disbursed application.
     */
    @Transactional
    public ApplicationView setLimitOverride(Long customerId, Long newLimitPaise, String note) {
        requireAdmin();
        if (customerId == null) {
            throw new BusinessException("INVALID_CUSTOMER", "customerId is required");
        }
        if (newLimitPaise != null && newLimitPaise < LoanMath.MIN_LOAN_PAISE) {
            throw new BusinessException("AMOUNT_TOO_LOW", "The limit is below the minimum of ₹1,000");
        }
        Long previous = limitOverrideRepository.findById(customerId)
                .map(CustomerLimitOverride::getLimitPaise).orElse(null);
        logIfChanged(customerId, null, "eligibleLimitPaise", str(previous), str(newLimitPaise));

        if (newLimitPaise == null) {
            limitOverrideRepository.deleteById(customerId);
        } else {
            CustomerLimitOverride row = limitOverrideRepository.findById(customerId)
                    .orElseGet(CustomerLimitOverride::new);
            row.setCustomerId(customerId);
            row.setLimitPaise(newLimitPaise);
            row.setNote(note);
            row.setSetBy(actorStaffIdOrNull());
            row.setSetAt(Instant.now());
            limitOverrideRepository.save(row);
        }

        // Push the new ceiling onto every live application. Clearing falls back to the salary rule,
        // so the borrower's stored limit is always consistent with what the resolver would answer.
        CustomerProfile profile = latestProfile(applicationRepository.findByCustomerId(customerId));
        eligibilityService.recomputeForCustomer(customerId,
                profile != null ? profile.getMonthlySalaryPaise() : null);

        // Tell the borrower only when the ceiling went UP. A cleared or reduced limit is not
        // something to push at them, and the offer/sanction notices cover the money they can draw.
        boolean increased = newLimitPaise != null && (previous == null || newLimitPaise > previous);
        if (increased) {
            eventPublisher.publishEvent(new LoanLimitRevisedEvent(
                    customerId, previous, newLimitPaise, Instant.now()));
        }

        return applicationRepository.findByCustomerId(customerId).stream()
                .max(Comparator.comparing(LoanApplication::getId))
                .map(ApplicationView::of)
                .orElse(null);
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
        total += jdbc.update("DELETE FROM customer_limit_override WHERE customer_id = ?", customerId);
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
     * Why this customer's file has no usable credit decision, with the provider chain behind it.
     *
     * <p>Resolves the SAME application the Customers list classifies — the newest one carrying a
     * saved profile, not simply the newest application. The two can differ when a fresh application
     * exists with no profile yet, and using the wrong one would explain a different file than the row
     * the staffer clicked, and point a re-run at it too.
     */
    public CaseFailureDetail caseFailure(Long customerId) {
        rejectDsa();
        requireVisible(customerId);
        List<LoanApplication> apps = applicationRepository.findByCustomerId(customerId);
        CustomerProfile profile = latestProfile(apps);
        Long applicationId = profile != null ? profile.getApplicationId()
                : apps.stream().map(LoanApplication::getId).max(Comparator.naturalOrder()).orElse(null);
        VerificationFailureService.CaseFailure failure =
                verificationFailureService.failure(applicationId);
        List<ProviderAttemptView> attempts = applicationId == null ? List.of()
                : providerAttempts.byApplicationId(List.of(applicationId))
                        .getOrDefault(applicationId, List.of()).stream()
                        .map(a -> new ProviderAttemptView(a.provider(), a.operation(),
                                a.httpStatus(), a.succeeded(), a.at()))
                        .toList();
        return new CaseFailureDetail(customerId, applicationId,
                failure.reason().name(), failure.reason().severity().name(),
                failure.reason().retryable(), failure.checkType(), attempts,
                com.navix.common.verification.ProviderAttemptDirectory.RETENTION_DAYS);
    }

    /**
     * A date of birth an admin is allowed to record. Deliberately loose — this is a correction tool,
     * not an eligibility check (the lending rules live in the flow service) — but it refuses the
     * three values that can only be typos, because a wrong DOB here goes straight to a bureau as a
     * real credit inquiry.
     */
    private static LocalDate requirePlausibleDob(LocalDate dob) {
        LocalDate today = LocalDate.now();
        if (!dob.isBefore(today)) {
            throw new BusinessException("INVALID_DOB", "Date of birth must be in the past");
        }
        if (dob.isAfter(today.minusYears(18))) {
            throw new BusinessException("INVALID_DOB", "The borrower must be at least 18 years old");
        }
        if (dob.isBefore(today.minusYears(100))) {
            throw new BusinessException("INVALID_DOB", "Date of birth is not plausible");
        }
        return dob;
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

    /**
     * Batched twin of {@link #latestProfile(List)}: same "newest application with a saved profile
     * wins" rule, but resolved against a pre-fetched application-id → profile map instead of one
     * {@code findByApplicationId} per application.
     */
    private CustomerProfile latestProfile(List<LoanApplication> apps, Map<Long, CustomerProfile> profileByAppId) {
        return apps.stream()
                .map(LoanApplication::getId)
                .filter(profileByAppId::containsKey)
                .max(Comparator.naturalOrder())
                .map(profileByAppId::get)
                .orElse(null);
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
