package com.navix.loan.service;

import com.navix.common.collections.CollectionCaseDirectory;
import com.navix.common.exception.BusinessException;
import com.navix.common.query.ColumnFilter;
import com.navix.common.query.FilterSpec;
import com.navix.common.query.SqlFacets;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.dto.LoanRegisterDtos.LoanRegisterRow;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff <b>loan register</b>: every loan the platform has ever disbursed, any status,
 * searchable and date-ranged — unlike {@code LoanController}'s {@code /api/loan/{id}} (one loan
 * at a time) or {@code TransactionService}'s ledger (one row per money-movement event, not per
 * loan). Backs {@code GET /api/loans}.
 *
 * <p><b>Enrichment is batched, deliberately.</b> {@code LoanDirectoryAdapter#toSummary} is the
 * existing precedent for "loan → application → profile → outstanding" resolution, but it does one
 * application lookup, one profile lookup and one outstanding computation <i>per loan</i> — fine for
 * collections' one-loan-at-a-time reads, wrong for a register that returns every loan at once. This
 * service instead loads applications/profiles in bulk maps and computes every loan's outstanding
 * balance through {@link RepaymentService#outstandingForAll}, so the whole register costs a small,
 * fixed number of queries regardless of how many loans exist.
 *
 * <p><b>Collections officer resolution.</b> {@code collection_case.assigned_officer_id} lives in
 * {@code navix-collections}, and {@code navix-loan} must not depend on it (the dependency runs the
 * other way — collections reads loans via {@code LoanDirectory}/{@code SettlementDirectory} ports in
 * navix-common). This service resolves the assignee through the {@link CollectionCaseDirectory} port
 * (mirroring {@link com.navix.common.collections.SettlementDirectory}, the same loan→collection_case
 * seam): one batched call resolves every loan on the page's officer id, then {@link StaffDirectory}
 * turns each distinct id into a display name — never a per-loan lookup.
 */
@Service
@RequiredArgsConstructor
public class LoanRegisterService {

    /** IST, matching {@code CustomerService}/{@code ApplicationFlowService} — day boundaries are an Indian business day. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LoanRepository loanRepository;
    private final LoanApplicationRepository applicationRepository;
    private final CustomerProfileRepository profileRepository;
    private final RepaymentService repaymentService;
    private final LoanMath loanMath;
    private final CollectionCaseDirectory collectionCaseDirectory;
    private final StaffDirectory staffDirectory;
    private final CustomerTrustSignalsService trustSignals;
    private final LoanRegisterQuery registerQuery;

    /**
     * The full register, optionally windowed by {@code disbursedOn} ({@code from}/{@code to}),
     * filtered by the caller's effective status and/or a free-text search over borrower name / PAN /
     * mobile / loan id. Status and search filtering happen here (after enrichment) rather than in SQL
     * because both depend on data that doesn't live on {@code loan} — the effective status is
     * computed on read (see {@link Loan#effectiveStatus}), and name/PAN/mobile live on the customer
     * profile.
     */
    @Transactional(readOnly = true)
    public List<LoanRegisterRow> list(String status, String q, LocalDate from, LocalDate to) {
        rejectDsa();
        requireRole("COLLECTION_HEAD");

        List<Loan> loans = loanRepository.findAllForRegister(from, to);
        if (loans.isEmpty()) {
            return List.of();
        }
        LocalDate today = LocalDate.now();
        List<Long> loanIds = loans.stream().map(Loan::getId).toList();
        // The whole window is enriched here, which is why the register page no longer calls this.
        List<LoanRegisterRow> rows = toRows(loans, computeLoanCycles(loans),
                repaymentService.outstandingForAll(loans, today),
                collectionCaseDirectory.assignedOfficerByLoanId(loanIds), today);

        if (status != null && !status.isBlank()) {
            String needle = status.trim();
            rows = rows.stream().filter(r -> needle.equalsIgnoreCase(r.status())).toList();
        }
        if (q != null && !q.isBlank()) {
            String needle = q.trim().toLowerCase();
            rows = rows.stream().filter(r -> matches(r, needle)).toList();
        }
        return rows;
    }

    // ------------------------------------------------------------------ paged register

    static final int MAX_PAGE_SIZE = 500;

    /** The two columns whose filter and sort run in Java: see {@link LoanRegisterQuery}. */
    private static final String OUTSTANDING = "outstandingPaise";
    private static final String OFFICER = "assignedOfficerName";

    /**
     * Stripped from a spec before it reaches SQL. {@link LoanRegisterQuery#PARSE_COLUMNS} registers
     * these two so they parse, with a placeholder SQL that must never be emitted.
     */
    private static FilterSpec sqlSpec(FilterSpec spec) {
        return spec.without(OUTSTANDING).without(OFFICER);
    }

    public record Page(List<LoanRegisterRow> rows, int page, int size, long total) {
    }

    /**
     * One page of the register. Search, the date window, the segment and every STORED or
     * day-derived column filter run in SQL ({@link LoanRegisterQuery}); only the ids on the page are
     * enriched, so the seven batched lookups cost a page, not the window.
     *
     * <p>Two columns cannot be answered by SQL: <b>outstanding</b> (compute-on-read money, never
     * re-derived in SQL) and the <b>collections officer</b> (another module's table). When a filter
     * or the sort names one of them, SQL still narrows by everything else and returns every matching
     * id; those two lookups — one batched payments read and one batched officer read — then run over
     * all of those ids, the survivors are filtered/sorted in Java, and only then is the page cut and
     * enriched. That worst case is the old cost minus the profile / application / trust enrichment,
     * and it is paid only while such a filter or sort is active.
     *
     * <p>{@code rejectDsa()} and the role check run first, exactly as in {@link #list}; the filter is
     * ANDed after them, so it can only narrow what the caller may already see.
     */
    @Transactional(readOnly = true)
    public Page page(String q, String segment, LocalDate from, LocalDate to, Map<String, String> filterParams,
            String sort, String dir, int page, int size) {
        rejectDsa();
        requireRole("COLLECTION_HEAD");
        LocalDate today = LocalDate.now();
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(1, page);
        int offset = (safePage - 1) * safeSize;

        LoanRegisterQuery.Filter filter = filter(q, segment, from, to, today);
        FilterSpec spec = FilterSpec.parse(filterParams, LoanRegisterQuery.PARSE_COLUMNS);
        FilterSpec sql = sqlSpec(spec);
        ColumnFilter.Num outstandingFilter = spec.get(OUTSTANDING) instanceof ColumnFilter.Num n ? n : null;
        ColumnFilter.In officerFilter = spec.get(OFFICER) instanceof ColumnFilter.In i ? i : null;
        boolean javaSort = OUTSTANDING.equals(sort) || OFFICER.equals(sort);

        if (outstandingFilter == null && officerFilter == null && !javaSort) {
            long total = registerQuery.count(filter, sql);
            List<LoanRegisterQuery.Hit> hits = registerQuery.pageHits(filter, sql, sort, dir, offset, safeSize);
            List<Loan> loans = loansInOrder(hits);
            List<Long> ids = loans.stream().map(Loan::getId).toList();
            return new Page(toRows(loans, cyclesOf(hits), repaymentService.outstandingForAll(loans, today),
                    collectionCaseDirectory.assignedOfficerByLoanId(ids), today), safePage, safeSize, total);
        }

        // Java stage. SQL order (the default due-date order when the sort is a Java one) is the
        // tie-break, because the sorts below are stable.
        List<LoanRegisterQuery.Hit> hits = registerQuery.allHits(filter, sql, javaSort ? null : sort, javaSort ? null : dir);
        List<Loan> all = loansInOrder(hits);
        Map<Long, Long> outstanding = repaymentService.outstandingForAll(all, today);
        Map<Long, Long> officerIds = collectionCaseDirectory.assignedOfficerByLoanId(all.stream().map(Loan::getId).toList());
        Map<Long, String> officerNames = officerNames(officerIds.values());

        List<Loan> kept = new ArrayList<>(all.size());
        for (Loan l : all) {
            if (outstandingFilter != null && !within(outstanding.getOrDefault(l.getId(), 0L), outstandingFilter)) {
                continue;
            }
            if (officerFilter != null && !officerFilter.values().contains(
                    officerKey(officerNames.get(officerIds.get(l.getId()))))) {
                continue;
            }
            kept.add(l);
        }
        if (javaSort) {
            boolean desc = !"asc".equalsIgnoreCase(dir);
            if (OUTSTANDING.equals(sort)) {
                Comparator<Loan> by = Comparator.comparing(l -> outstanding.getOrDefault(l.getId(), 0L));
                kept.sort(desc ? by.reversed() : by);
            } else {
                // Nulls last in both directions, as the SQL sorts do: reversing a nulls-last
                // comparator would put them first.
                Comparator<String> order = desc ? Comparator.<String>reverseOrder() : Comparator.<String>naturalOrder();
                kept.sort(Comparator.comparing(
                        l -> lower(officerNames.get(officerIds.get(l.getId()))), Comparator.nullsLast(order)));
            }
        }
        long total = kept.size();
        List<Loan> pageLoans = kept.subList((int) Math.min(total, offset), (int) Math.min(total, (long) offset + safeSize));
        return new Page(toRows(pageLoans, cyclesOf(hits), outstanding, officerIds, today), safePage, safeSize, total);
    }

    /**
     * The values one column's checklist can offer, inside the same window, search, segment and
     * (stored) column filters as the page. Status comes from SQL. The officer column is not in SQL, so
     * its names come from the batched officer read over the ids those same clauses match; the two
     * Java-side filters (outstanding, officer) are not applied to a facet, so a dropdown can offer a
     * value that the active outstanding filter would empty — cheaper than computing every balance to
     * populate a menu. Identity columns have no facet: {@link SqlFacets} answers only {@code IN} columns.
     */
    @Transactional(readOnly = true)
    public SqlFacets.Facet facet(String column, String q, String segment, LocalDate from, LocalDate to,
            Map<String, String> filterParams) {
        rejectDsa();
        requireRole("COLLECTION_HEAD");
        LoanRegisterQuery.Filter filter = filter(q, segment, from, to, LocalDate.now());
        FilterSpec spec = sqlSpec(FilterSpec.parse(filterParams, LoanRegisterQuery.PARSE_COLUMNS)).without(column);
        if (OFFICER.equals(column)) {
            List<Long> ids = registerQuery.allHits(filter, spec, null, null).stream()
                    .map(LoanRegisterQuery.Hit::loanId).toList();
            Map<Long, Long> officerIds = collectionCaseDirectory.assignedOfficerByLoanId(ids);
            Map<Long, String> names = officerNames(officerIds.values());
            Set<String> values = new LinkedHashSet<>();
            names.values().stream().filter(Objects::nonNull).sorted(String.CASE_INSENSITIVE_ORDER).forEach(values::add);
            // A loan nobody is working is a value people filter for, not an absence to hide.
            if (officerIds.size() < ids.size() || names.containsValue(null)) {
                values.add(ColumnFilter.BLANK);
            }
            List<String> out = new ArrayList<>(values);
            boolean truncated = out.size() > SqlFacets.MAX_VALUES;
            return new SqlFacets.Facet(truncated ? out.subList(0, SqlFacets.MAX_VALUES) : out, truncated);
        }
        return registerQuery.facet(filter, spec, column);
    }

    private static LoanRegisterQuery.Filter filter(String q, String segment, LocalDate from, LocalDate to,
            LocalDate today) {
        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        String seg = segment == null || segment.isBlank() || "all".equals(segment) ? null : segment;
        return new LoanRegisterQuery.Filter(needle, from, to, seg, today);
    }

    /** Loans for these hits, in the hits' order; a hit whose loan vanished meanwhile is dropped. */
    private List<Loan> loansInOrder(List<LoanRegisterQuery.Hit> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }
        Map<Long, Loan> byId = loanRepository.findAllById(hits.stream().map(LoanRegisterQuery.Hit::loanId).toList())
                .stream().collect(Collectors.toMap(Loan::getId, l -> l, (a, b) -> a));
        return hits.stream().map(h -> byId.get(h.loanId())).filter(Objects::nonNull).toList();
    }

    private static Map<Long, Integer> cyclesOf(List<LoanRegisterQuery.Hit> hits) {
        Map<Long, Integer> out = new HashMap<>();
        hits.forEach(h -> out.put(h.loanId(), h.loanCycle()));
        return out;
    }

    /** Distinct officer id -> display name; a staffer that no longer resolves maps to null (shown "Unallocated"). */
    private Map<Long, String> officerNames(java.util.Collection<Long> officerIds) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (Long id : officerIds) {
            if (id != null) {
                names.computeIfAbsent(id, k -> staffDirectory.findStaff(k).map(StaffSummary::name).orElse(null));
            }
        }
        return names;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    /** The checklist's value for an officer: their name, or the blank sentinel when there is none to show. */
    private static String officerKey(String name) {
        return name == null ? ColumnFilter.BLANK : name;
    }

    /** Inclusive bounds, rupees to paise HALF_UP on the OPERAND — the same rule {@code SqlFilter} applies. */
    private static boolean within(long paise, ColumnFilter.Num f) {
        return (f.min() == null || paise >= stored(f.min())) && (f.max() == null || paise <= stored(f.max()));
    }

    private static long stored(BigDecimal rupees) {
        return rupees.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * Enrich exactly these loans into rows. The balances, officers and cycles are passed in so the
     * caller decides their scope: the whole window for {@link #list}, the page for {@link #page}
     * (or every matching loan, for the two it filters on in Java).
     */
    private List<LoanRegisterRow> toRows(List<Loan> loans, Map<Long, Integer> cycleByLoanId,
            Map<Long, Long> outstandingByLoanId, Map<Long, Long> officerIdByLoanId, LocalDate today) {
        if (loans.isEmpty()) {
            return List.of();
        }
        List<Long> loanIds = loans.stream().map(Loan::getId).toList();
        // --- bulk lookups: application by loan id, profile by application id ---
        Map<Long, LoanApplication> appByLoanId = applicationRepository.findByLoanIdIn(loanIds).stream()
                .filter(a -> a.getLoanId() != null)
                .collect(Collectors.toMap(LoanApplication::getLoanId, a -> a, (a, b) -> a));
        List<Long> appIds = appByLoanId.values().stream().map(LoanApplication::getId).toList();
        Map<Long, CustomerProfile> profileByAppId = appIds.isEmpty() ? Map.of()
                : profileRepository.findByApplicationIdIn(appIds).stream()
                        .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
        Map<Long, String> officerNameById = new HashMap<>();
        Map<Long, com.navix.common.loan.TrustSignals> trustByCustomer = trustSignals.forCustomers(
                loans.stream().map(Loan::getCustomerId).filter(Objects::nonNull).distinct().toList());
        List<LoanRegisterRow> rows = new ArrayList<>(loans.size());
        for (Loan loan : loans) {
            LoanApplication app = appByLoanId.get(loan.getId());
            CustomerProfile profile = app != null ? profileByAppId.get(app.getId()) : null;
            LoanStatus effective = loan.effectiveStatus(today);
            // DPD measured against the right end date. A settled loan's lateness is frozen at the
            // day it closed — running it to `today` would show a loan that was repaid on time as
            // months overdue, growing forever, which is how the whole closed book would read.
            LocalDate dpdAsOf = loan.getClosedOn() != null ? loan.getClosedOn() : today;
            int dpd = loanMath.daysPastDue(loan.getDueDate(), dpdAsOf);
            Long officerId = officerIdByLoanId.get(loan.getId());
            String officerName = officerId == null ? null
                    : officerNameById.computeIfAbsent(officerId,
                            id -> staffDirectory.findStaff(id).map(StaffSummary::name).orElse(null));

            rows.add(new LoanRegisterRow(
                    loan.getId(),
                    loan.getCustomerId(),
                    app != null ? app.getId() : null,
                    profile != null ? profile.getFullName() : null,
                    profile != null ? profile.getMobile() : null,
                    profile != null ? profile.getPan() : null,
                    cycleByLoanId.getOrDefault(loan.getId(), 1),
                    loan.getPrincipal(),
                    loan.getNetDisbursed(),
                    loan.getTotalRepayable(),
                    outstandingByLoanId.getOrDefault(loan.getId(), 0L),
                    app != null ? app.getSanctionedAmountPaise() : null,
                    toIstDate(app != null ? app.getSanctionedAt() : null),
                    loan.getDisbursedOn(),
                    loan.getDueDate(),
                    loan.getClosedOn(),
                    app != null ? app.getSalaryCreditDay() : null,
                    effective != null ? effective.name() : null,
                    dpd,
                    officerId,
                    officerName,
                    loan.getDisbursalTxnRef(),
                    trustByCustomer.get(loan.getCustomerId())));
        }
        return rows;
    }

    /** 1-based loan cycle per customer: their loans ordered by {@code disbursedOn}, tie-break id. */
    private Map<Long, Integer> computeLoanCycles(List<Loan> loans) {
        Map<Long, List<Loan>> byCustomer = loans.stream().collect(Collectors.groupingBy(Loan::getCustomerId));
        Map<Long, Integer> cycleByLoanId = new HashMap<>();
        for (List<Loan> customerLoans : byCustomer.values()) {
            List<Loan> ordered = customerLoans.stream()
                    .sorted(Comparator.comparing(Loan::getDisbursedOn, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Loan::getId))
                    .toList();
            for (int i = 0; i < ordered.size(); i++) {
                cycleByLoanId.put(ordered.get(i).getId(), i + 1);
            }
        }
        return cycleByLoanId;
    }

    private static LocalDate toIstDate(Instant instant) {
        return instant == null ? null : instant.atZone(IST).toLocalDate();
    }

    /** Free-text match against borrower name, masked PAN, mobile, or loan id. */
    private static boolean matches(LoanRegisterRow r, String needle) {
        if (r.borrowerName() != null && r.borrowerName().toLowerCase().contains(needle)) {
            return true;
        }
        if (r.pan() != null && r.pan().toLowerCase().contains(needle)) {
            return true;
        }
        if (r.mobile() != null && r.mobile().contains(needle)) {
            return true;
        }
        return r.loanId() != null && String.valueOf(r.loanId()).contains(needle);
    }

    // ---------------------------------------------------------------- authz
    // Copied from CustomerService (same private-helper shape) rather than shared, so this service
    // has no compile-time coupling to CustomerService's surface.

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
}
