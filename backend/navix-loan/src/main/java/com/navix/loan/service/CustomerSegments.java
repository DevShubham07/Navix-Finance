package com.navix.loan.service;

import com.navix.loan.dto.CustomerDtos.CustomerSummary;
import com.navix.loan.dto.CustomerDtos.CustomerSummaryCounts;

import java.util.List;
import java.util.Set;

/**
 * Lifecycle segments for one customer row — the server-side twin of the frontend's
 * {@code lib/customers/segments.ts}, which is the file this is a faithful port of (same sets, same
 * precedence). It exists so {@link CustomerService#bookStats()} can roll the caller's book up on
 * the server instead of shipping every row to the browser to be counted there.
 *
 * <p>Deliberately pure and static: no repositories, no actor, nothing to mock — the segment of a
 * row is a function of two status strings and nothing else. The SQL twin of the same rules lives in
 * {@link CustomerBookQuery} (for filtering/counting a page in the database); this one classifies
 * rows already in hand.
 */
public final class CustomerSegments {

    private CustomerSegments() {
    }

    private static final Set<String> OVERDUE_LOAN = Set.of("OVERDUE", "IN_COLLECTIONS");
    private static final Set<String> ACTIVE_LOAN = Set.of("ACTIVE", "DISBURSING");
    private static final Set<String> OVERDUE_APP = Set.of("OVERDUE", "DEFAULTED");
    private static final Set<String> ACTIVE_APP = Set.of("DISBURSED", "ACTIVE");
    /**
     * {@code DRAFT} is deliberately NOT "pending": the DRAFT row is minted right after mobile-OTP,
     * so it means "abandoned mid-onboarding, nobody can action it" — a chase/call target, not a
     * staff queue item.
     */
    private static final Set<String> PENDING_APP = Set.of("KYC_PENDING", "PRE_APPROVED");
    private static final Set<String> REVIEW_APP =
            Set.of("REVIEW_PENDING", "CREDIT_EXEC_PENDING", "CREDIT_HEAD_PENDING");
    /**
     * {@code SANCTIONED} belongs here: the credit team has decided and the borrower is walking the
     * post-approval journey (V45). Without it the row falls through to the default and shows as
     * "pending" — decided but invisible.
     */
    private static final Set<String> APPROVED_APP = Set.of(
            "KYC_APPROVED", "CREDIT_EXEC_APPROVED", "CREDIT_HEAD_APPROVED", "SANCTIONED");
    /**
     * Sanctioned, money not yet released. {@code ACCOUNTANT_PENDING} is retired (V48, historical
     * rows only) but represents the same "waiting for money to move out" stage — kept together
     * rather than split across segments.
     */
    private static final Set<String> DISBURSEMENT_PENDING_APP =
            Set.of("DISBURSEMENT_PENDING", "ACCOUNTANT_PENDING");
    private static final Set<String> REJECTED_APP = Set.of("KYC_REJECTED", "REJECTED");
    private static final Set<String> CLOSED_APP = Set.of("CLOSED", "WRITTEN_OFF", "CANCELLED");

    /**
     * The lifecycle segment for one row. <b>Loan state outranks application status</b> — the
     * application aggregate stays {@code ACTIVE} for the entire overdue window, so
     * {@code latestStatus} alone can never say "Overdue".
     *
     * <p>Never {@code unallocated} or {@code all}: those two are not lifecycle stages
     * ({@code unallocated} is an ownership overlay, {@code all} is the default view), so they are
     * counted separately in {@link #counts}. An unrecognized or absent status falls through to
     * {@code pending}.
     */
    public static String segmentOf(String loanStatus, String latestStatus) {
        String loan = loanStatus;
        String app = latestStatus;

        if (loan != null && OVERDUE_LOAN.contains(loan)) {
            return "overdue";
        }
        if (app != null && OVERDUE_APP.contains(app)) {
            return "overdue";
        }

        if (loan != null && ACTIVE_LOAN.contains(loan)) {
            return "active";
        }
        if (app != null && ACTIVE_APP.contains(app)) {
            return "active";
        }

        if (app != null && REVIEW_APP.contains(app)) {
            return "review";
        }
        if (app != null && APPROVED_APP.contains(app)) {
            return "approved";
        }
        if (app != null && DISBURSEMENT_PENDING_APP.contains(app)) {
            return "disbursementPending";
        }
        if ("DISBURSEMENT_FAILED".equals(app)) {
            return "hold";
        }
        if ("DRAFT".equals(app)) {
            return "incomplete";
        }
        if (app != null && REJECTED_APP.contains(app)) {
            return "rejected";
        }
        if (app != null && CLOSED_APP.contains(app)) {
            return "closed";
        }
        if (app != null && PENDING_APP.contains(app)) {
            return "pending";
        }

        return "pending";
    }

    /**
     * One pass over {@code rows} → the segment-chip counts. {@code all} is every row and
     * {@code unallocated} <b>overlays</b> the lifecycle segments (a row nobody owns is counted both
     * in its lifecycle segment and in {@code unallocated}), exactly as the frontend's
     * {@code segmentCounts} and {@link CustomerService#summary} do.
     */
    public static CustomerSummaryCounts counts(List<CustomerSummary> rows) {
        long all = 0;
        long incomplete = 0;
        long pending = 0;
        long review = 0;
        long approved = 0;
        long disbursementPending = 0;
        long active = 0;
        long overdue = 0;
        long hold = 0;
        long rejected = 0;
        long closed = 0;
        long unallocated = 0;
        for (CustomerSummary c : rows) {
            all++;
            switch (segmentOf(c.loanStatus(), c.latestStatus())) {
                case "incomplete" -> incomplete++;
                case "review" -> review++;
                case "approved" -> approved++;
                case "disbursementPending" -> disbursementPending++;
                case "active" -> active++;
                case "overdue" -> overdue++;
                case "hold" -> hold++;
                case "rejected" -> rejected++;
                case "closed" -> closed++;
                default -> pending++;
            }
            if (c.ownerStaffId() == null) {
                unallocated++;
            }
        }
        return new CustomerSummaryCounts(all, incomplete, pending, review, approved,
                disbursementPending, active, overdue, hold, rejected, closed, unallocated);
    }
}
