package com.navix.loan.service;

import com.navix.common.collections.CollectionCaseDirectory;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.common.util.Masking;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.dto.LoanRegisterDtos.LoanRegisterRow;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        // --- bulk lookups: application by loan id, profile by application id ---
        List<Long> loanIds = loans.stream().map(Loan::getId).toList();
        Map<Long, LoanApplication> appByLoanId = applicationRepository.findByLoanIdIn(loanIds).stream()
                .filter(a -> a.getLoanId() != null)
                .collect(Collectors.toMap(LoanApplication::getLoanId, a -> a, (a, b) -> a));
        List<Long> appIds = appByLoanId.values().stream().map(LoanApplication::getId).toList();
        Map<Long, CustomerProfile> profileByAppId = appIds.isEmpty() ? Map.of()
                : profileRepository.findByApplicationIdIn(appIds).stream()
                        .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));

        // --- balances: one batched pass, agrees with the repay page / collections by construction ---
        Map<Long, Long> outstandingByLoanId = repaymentService.outstandingForAll(loans, LocalDate.now());

        // --- loan cycle: 1-based index per customer, ordered by disbursedOn (tie-break loan id) ---
        Map<Long, Integer> cycleByLoanId = computeLoanCycles(loans);

        // --- collections officer: one batched port call, then a per-distinct-id name lookup ---
        Map<Long, Long> officerIdByLoanId = collectionCaseDirectory.assignedOfficerByLoanId(loanIds);
        Map<Long, String> officerNameById = new HashMap<>();

        LocalDate today = LocalDate.now();
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
                    Masking.maskPan(profile != null ? profile.getPan() : null),
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
                    loan.getDisbursalTxnRef()));
        }

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
        if (r.panMasked() != null && r.panMasked().toLowerCase().contains(needle)) {
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
