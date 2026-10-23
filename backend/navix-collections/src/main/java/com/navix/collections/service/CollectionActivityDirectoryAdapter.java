package com.navix.collections.service;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.CollectionPayment;
import com.navix.collections.entity.CollectionPaymentStatus;
import com.navix.collections.entity.Settlement;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.CollectionPaymentRepository;
import com.navix.collections.repository.InteractionLogRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.collections.CollectionActivityDirectory;
import com.navix.common.collections.CollectionStaffStats;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Collections-module implementation of the {@link CollectionActivityDirectory} port: a whole roster's
 * collections work (calls by outcome, payments, settlements, validations, open cases) in a fixed
 * number of batched queries. Mirrors {@link CollectionCaseDirectoryAdapter} — wired by component scan,
 * consumed by the loan module's staff-performance dashboard so collections staff are measured on
 * their own work without navix-loan depending on navix-collections.
 */
@Component
@RequiredArgsConstructor
public class CollectionActivityDirectoryAdapter implements CollectionActivityDirectory {

    /** Same set {@code CollectionsService} drops from the worklist — a settled loan is not an open case. */
    private static final Set<String> TERMINAL_LOAN_STATUSES = Set.of("CLOSED", "REPAID", "WRITTEN_OFF");

    private final InteractionLogRepository interactionRepository;
    private final CollectionPaymentRepository paymentRepository;
    private final SettlementRepository settlementRepository;
    private final CollectionCaseRepository caseRepository;
    private final LoanDirectory loanDirectory;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> callCountsByStaff(Collection<Long> staffIds, Instant from, Instant to) {
        if (staffIds == null || staffIds.isEmpty() || from == null || to == null) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        for (InteractionLogRepository.StaffCallCount row
                : interactionRepository.countByStaffInWindow(staffIds, from, to)) {
            result.put(row.getStaffId(), row.getCount());
        }
        return result;
    }

    /**
     * Five fixed reads for the whole roster — outcome counts, interaction instants, payments,
     * settlements, assigned cases (+ one batched loan lookup to drop settled ones) — bucketed in
     * memory. Never a per-staffer query.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, StaffActivity> statsByStaff(Collection<Long> staffIds, Instant from, Instant to) {
        if (staffIds == null || staffIds.isEmpty() || from == null || to == null) {
            return Map.of();
        }
        Map<Long, Tally> tallies = new HashMap<>();

        for (InteractionLogRepository.StaffOutcomeCount row
                : interactionRepository.countByStaffAndOutcomeInWindow(staffIds, from, to)) {
            Tally t = tallies.computeIfAbsent(row.getStaffId(), id -> new Tally());
            long n = row.getCount();
            t.interactions += n;
            String outcome = row.getOutcome() == null ? "" : row.getOutcome().toUpperCase();
            switch (outcome) {
                case "CONNECTED" -> t.connected += n;
                case "PROMISE_TO_PAY" -> t.promisesToPay += n;
                case "PAID" -> t.paidOutcomes += n;
                case "NO_ANSWER" -> t.noAnswer += n;
                case "DISPUTED" -> t.disputed += n;
                default -> { }
            }
        }
        for (InteractionLogRepository.StaffInstant row
                : interactionRepository.activityByStaffInWindow(staffIds, from, to)) {
            tallies.computeIfAbsent(row.getStaffId(), id -> new Tally()).activity.add(row.getLoggedAt());
        }

        Set<Long> roster = new HashSet<>(staffIds);
        for (CollectionPayment p : paymentRepository.findTouchedByStaffInWindow(staffIds, from, to)) {
            if (roster.contains(p.getRaisedBy()) && inWindow(p.getRaisedAt(), from, to)) {
                Tally t = tallies.computeIfAbsent(p.getRaisedBy(), id -> new Tally());
                long amount = p.getAmountPaise() == null ? 0 : p.getAmountPaise();
                t.paymentsRaised++;
                t.paymentsRaisedPaise += amount;
                if (p.getStatus() == CollectionPaymentStatus.VALIDATED) {
                    t.paymentsRecovered++;
                    t.paymentsRecoveredPaise += amount;
                } else if (p.getStatus() == CollectionPaymentStatus.REJECTED) {
                    t.paymentsRejected++;
                } else if (p.getStatus() != null && p.getStatus().isPending()) {
                    t.paymentsPending++;
                }
                t.activity.add(p.getRaisedAt());
            }
            if (p.getValidatedBy() != null && roster.contains(p.getValidatedBy())
                    && inWindow(p.getValidatedAt(), from, to)) {
                Tally t = tallies.computeIfAbsent(p.getValidatedBy(), id -> new Tally());
                if (p.getStatus() == CollectionPaymentStatus.VALIDATED) {
                    t.paymentsValidated++;
                    t.paymentsValidatedPaise += p.getAmountPaise() == null ? 0 : p.getAmountPaise();
                } else if (p.getStatus() == CollectionPaymentStatus.REJECTED) {
                    t.paymentsValidationRejected++;
                }
                t.activity.add(p.getValidatedAt());
            }
        }

        for (Settlement s : settlementRepository.findTouchedByStaffInWindow(staffIds, from, to)) {
            long amount = s.getSettlementAmount() == null ? 0 : s.getSettlementAmount();
            if (roster.contains(s.getProposedBy()) && inWindow(s.getCreatedAt(), from, to)) {
                Tally t = tallies.computeIfAbsent(s.getProposedBy(), id -> new Tally());
                t.settlementsProposed++;
                t.settlementsProposedPaise += amount;
                t.activity.add(s.getCreatedAt());
            }
            if (s.getApprovedBy() != null && roster.contains(s.getApprovedBy())
                    && inWindow(s.getApprovedAt(), from, to)) {
                Tally t = tallies.computeIfAbsent(s.getApprovedBy(), id -> new Tally());
                t.settlementsApproved++;
                t.settlementsApprovedPaise += amount;
                t.activity.add(s.getApprovedAt());
            }
            if (s.getRejectedBy() != null && roster.contains(s.getRejectedBy())
                    && inWindow(s.getRejectedAt(), from, to)) {
                Tally t = tallies.computeIfAbsent(s.getRejectedBy(), id -> new Tally());
                t.settlementsRejected++;
                t.activity.add(s.getRejectedAt());
            }
        }

        // Live, not windowed: what is on their desk right now, minus cases whose loan has settled.
        List<CollectionCase> cases = caseRepository.findByAssignedOfficerIdIn(staffIds);
        if (!cases.isEmpty()) {
            Map<Long, LoanSummary> loans = loanDirectory.findLoans(
                    cases.stream().map(CollectionCase::getLoanId).filter(Objects::nonNull).distinct().toList());
            for (CollectionCase c : cases) {
                LoanSummary loan = loans.get(c.getLoanId());
                if (loan != null && loan.status() != null && TERMINAL_LOAN_STATUSES.contains(loan.status())) {
                    continue;
                }
                tallies.computeIfAbsent(c.getAssignedOfficerId(), id -> new Tally()).casesNow++;
            }
        }

        Map<Long, StaffActivity> result = new HashMap<>();
        tallies.forEach((staffId, t) -> result.put(staffId, t.toActivity()));
        return result;
    }

    private static boolean inWindow(Instant at, Instant from, Instant to) {
        return at != null && !at.isBefore(from) && at.isBefore(to);
    }

    /** Mutable accumulator, one per staffer, frozen into the immutable port types at the end. */
    private static final class Tally {
        long interactions;
        long connected;
        long promisesToPay;
        long paidOutcomes;
        long noAnswer;
        long disputed;
        long casesNow;
        long paymentsRaised;
        long paymentsRaisedPaise;
        long paymentsRecovered;
        long paymentsRecoveredPaise;
        long paymentsPending;
        long paymentsRejected;
        long settlementsProposed;
        long settlementsProposedPaise;
        long settlementsApproved;
        long settlementsApprovedPaise;
        long settlementsRejected;
        long paymentsValidated;
        long paymentsValidatedPaise;
        long paymentsValidationRejected;
        final List<Instant> activity = new ArrayList<>();

        StaffActivity toActivity() {
            return new StaffActivity(new CollectionStaffStats(
                    interactions, connected, promisesToPay, paidOutcomes, noAnswer, disputed, casesNow,
                    paymentsRaised, paymentsRaisedPaise, paymentsRecovered, paymentsRecoveredPaise,
                    paymentsPending, paymentsRejected,
                    settlementsProposed, settlementsProposedPaise,
                    settlementsApproved, settlementsApprovedPaise, settlementsRejected,
                    paymentsValidated, paymentsValidatedPaise, paymentsValidationRejected),
                    List.copyOf(activity));
        }
    }
}
