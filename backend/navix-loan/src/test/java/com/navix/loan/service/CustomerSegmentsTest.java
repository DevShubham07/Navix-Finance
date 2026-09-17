package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.loan.dto.BureauState;
import com.navix.loan.dto.CustomerDtos.CustomerSummary;
import com.navix.loan.dto.CustomerDtos.CustomerSummaryCounts;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link CustomerSegments} to the frontend's {@code lib/customers/segments.ts}, which it is a
 * port of. Every status that file names is walked here, together with the precedence rules that
 * decide between them — if the two drift, the dashboard's server-computed counts and the
 * Customers page's chips start disagreeing about the same book.
 */
class CustomerSegmentsTest {

    /** One case: what the two statuses are, and the segment they must produce. */
    private record Case(String loanStatus, String latestStatus, String expected) {
    }

    @Test
    void segmentOfWalksEveryStatusTheFrontendHandles() {
        List<Case> cases = List.of(
                // --- loan state outranks application status -------------------------------------
                new Case("OVERDUE", "ACTIVE", "overdue"),
                new Case("IN_COLLECTIONS", "ACTIVE", "overdue"),
                // …even when the application has moved on: the aggregate stays ACTIVE (and can even
                // read CLOSED) while the loan itself is still being chased.
                new Case("OVERDUE", "CLOSED", "overdue"),
                new Case("ACTIVE", "REJECTED", "active"),
                new Case("DISBURSING", "SANCTIONED", "active"),

                // --- application status, in the frontend's order --------------------------------
                new Case(null, "OVERDUE", "overdue"),
                new Case(null, "DEFAULTED", "overdue"),
                new Case(null, "DISBURSED", "active"),
                new Case(null, "ACTIVE", "active"),
                new Case(null, "REVIEW_PENDING", "review"),
                new Case(null, "CREDIT_EXEC_PENDING", "review"),
                new Case(null, "CREDIT_HEAD_PENDING", "review"),
                new Case(null, "KYC_APPROVED", "approved"),
                new Case(null, "CREDIT_EXEC_APPROVED", "approved"),
                new Case(null, "CREDIT_HEAD_APPROVED", "approved"),
                // Decided by credit, borrower now walking the offer journey (V45). Without this the
                // row would fall through to the default and read as "pending" — decided but invisible.
                new Case(null, "SANCTIONED", "approved"),
                new Case(null, "DISBURSEMENT_PENDING", "disbursementPending"),
                // Retired in V48 (historical rows only) but the same "waiting for money to move out"
                // stage, so it is not split off into its own segment.
                new Case(null, "ACCOUNTANT_PENDING", "disbursementPending"),
                new Case(null, "DISBURSEMENT_FAILED", "hold"),
                // DRAFT is minted right after mobile-OTP: abandoned mid-onboarding, a chase target,
                // deliberately NOT a staff queue item.
                new Case(null, "DRAFT", "incomplete"),
                new Case(null, "KYC_REJECTED", "rejected"),
                new Case(null, "REJECTED", "rejected"),
                new Case(null, "CLOSED", "closed"),
                new Case(null, "WRITTEN_OFF", "closed"),
                new Case(null, "CANCELLED", "closed"),
                new Case(null, "KYC_PENDING", "pending"),
                new Case(null, "PRE_APPROVED", "pending"),

                // --- nothing to go on falls through to "pending" --------------------------------
                new Case(null, null, "pending"),
                new Case(null, "SOMETHING_NEW", "pending"),
                // A loan status nobody recognizes must not hijack the row from its application.
                new Case("SOMETHING_NEW", "DRAFT", "incomplete"));

        List<String> failures = new ArrayList<>();
        for (Case c : cases) {
            String actual = CustomerSegments.segmentOf(c.loanStatus(), c.latestStatus());
            if (!c.expected().equals(actual)) {
                failures.add("loan=" + c.loanStatus() + " app=" + c.latestStatus()
                        + " expected " + c.expected() + " but was " + actual);
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void countsTallyEachSegmentOnceAndOverlayUnallocated() {
        List<CustomerSummary> book = List.of(
                row("OVERDUE", "ACTIVE", 7L),      // overdue (loan wins)
                row(null, "ACTIVE", 7L),           // active
                row(null, "CREDIT_EXEC_PENDING", 7L),  // review
                row(null, "SANCTIONED", 7L),       // approved
                row(null, "DISBURSEMENT_PENDING", 7L), // disbursementPending
                row(null, "DISBURSEMENT_FAILED", 7L),  // hold
                row(null, "REJECTED", 7L),         // rejected
                row(null, "CLOSED", 7L),           // closed
                row(null, "KYC_PENDING", 7L),      // pending
                row(null, "DRAFT", null));         // incomplete AND unallocated

        CustomerSummaryCounts counts = CustomerSegments.counts(book);

        assertThat(counts.all()).isEqualTo(10);
        assertThat(counts.overdue()).isEqualTo(1);
        assertThat(counts.active()).isEqualTo(1);
        assertThat(counts.review()).isEqualTo(1);
        assertThat(counts.approved()).isEqualTo(1);
        assertThat(counts.disbursementPending()).isEqualTo(1);
        assertThat(counts.hold()).isEqualTo(1);
        assertThat(counts.rejected()).isEqualTo(1);
        assertThat(counts.closed()).isEqualTo(1);
        assertThat(counts.pending()).isEqualTo(1);
        assertThat(counts.incomplete()).isEqualTo(1);
        // Unallocated OVERLAYS the lifecycle segments rather than replacing one: the ownerless row
        // is counted both as incomplete and as unallocated, so the chips never lose a customer.
        assertThat(counts.unallocated()).isEqualTo(1);
        assertThat(counts.incomplete() + counts.pending() + counts.review() + counts.approved()
                + counts.disbursementPending() + counts.active() + counts.overdue() + counts.hold()
                + counts.rejected() + counts.closed()).isEqualTo(counts.all());
    }

    @Test
    void countsOfAnEmptyBookAreAllZero() {
        CustomerSummaryCounts counts = CustomerSegments.counts(List.of());

        assertThat(counts.all()).isZero();
        assertThat(counts.pending()).isZero();
        assertThat(counts.unallocated()).isZero();
    }

    /** Only the three fields segmenting reads carry a value; the rest of the row is irrelevant here. */
    private static CustomerSummary row(String loanStatus, String latestStatus, Long ownerStaffId) {
        return new CustomerSummary(
                1L, null, null, null, 1, 0, latestStatus, 0L, null, null,
                loanStatus, ownerStaffId, null, BureauState.NOT_FETCHED, null, null,
                null, null, null, null, null, false, null, null, null,
                null, null, null, null, "NONE", "NONE", false);
    }
}
