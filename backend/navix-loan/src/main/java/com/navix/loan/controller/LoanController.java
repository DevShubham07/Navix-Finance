package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.dto.LoanDtos.OutstandingScheduleView;
import com.navix.loan.dto.LoanDtos.OutstandingView;
import com.navix.loan.dto.LoanDtos.PaymentView;
import com.navix.loan.dto.LoanDtos.TransactionView;
import com.navix.loan.entity.Payment;
import com.navix.loan.entity.Loan;
import com.navix.loan.service.RepaymentService;
import com.navix.loan.service.TransactionService;
import com.navix.loan.service.TransactionService.BorrowerRef;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Disbursed-loan read endpoints (the loan ledger). Application creation/lifecycle lives under
 * {@code /api/applications}; repayments under {@code /api/loan/{loanId}/repayments}.
 */
@RestController
@RequestMapping("/api/loan")
@RequiredArgsConstructor
public class LoanController {

    private final RepaymentService repaymentService;
    private final TransactionService transactionService;

    @GetMapping("/{loanId}")
    public ApiResponse<LoanView> getLoan(@PathVariable Long loanId) {
        // Owner-or-staff: a borrower may only read their own loan (see requireReadableLoan).
        var loan = repaymentService.requireReadableLoan(loanId);
        // Single source of truth for "amount owed": the penalty/prepayment-aware balance, and an
        // effective status so a past-due ACTIVE loan reads as OVERDUE (matches the repay page).
        long owed = repaymentService.outstandingBreakdownAsOf(loan, null).outstandingPaise();
        return ApiResponse.ok(LoanView.of(loan, owed, loan.effectiveStatus(LocalDate.now())));
    }

    /**
     * Accountant queue: repayments awaiting proof verification, across all loans. A literal path so
     * it is selected over {@code /{loanId}} (PathPattern ranks literal segments above captures).
     */
    @GetMapping("/pending-repayments")
    public ApiResponse<List<PaymentView>> pendingRepayments() {
        // Reuse the staff-only ledger's loan → customer enrichment so the verifier can identify the
        // borrower before accepting a transfer, without rebuilding the whole ledger for a handful of
        // rows. Borrower repayment endpoints still use PaymentView.of(payment).
        List<Payment> pending = repaymentService.listPending();
        Map<Long, BorrowerRef> byLoan = transactionService.borrowersByLoanId(
                pending.stream().map(Payment::getLoanId).distinct().toList());
        return ApiResponse.ok(pending.stream().map(payment -> {
            BorrowerRef ref = byLoan.get(payment.getLoanId());
            return repaymentService.view(payment,
                    ref != null ? ref.customerId() : null,
                    ref != null ? ref.borrowerName() : null);
        }).toList());
    }

    /**
     * Accountant transactions ledger: all money movement (OUTGOING disbursals + INCOMING
     * repayments), company-wide, optionally filtered by {@code direction} and a free-text
     * {@code q} (borrower name / mobile / loan id). Literal path → ranks above {@code /{loanId}}.
     */
    @GetMapping("/transactions")
    public ApiResponse<TransactionService.TransactionPage> transactions(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size) {
        // The company-wide ledger carries borrower PII (names + full PAN) — staff-only, and scoped to
        // the roles the UI exposes it to (Accountant + Admin). Closes a leak where any authenticated
        // token (incl. a BORROWER JWT) could read every borrower's transactions. Optional from/to filter
        // a statement period server-side.
        requireRole("ACCOUNTANT", "ADMIN");
        return ApiResponse.ok(transactionService.listTransactions(q, direction, from, to, page, size));
    }

    /** Reject any actor whose role isn't in {@code allowed} (ADMIN included explicitly where needed). */
    private void requireRole(String... allowed) {
        String role = ActorContext.get().role();
        for (String r : allowed) {
            if (r.equals(role)) {
                return;
            }
        }
        throw new BusinessException("FORBIDDEN_ROLE", "This action requires role " + String.join(" or ", allowed));
    }

    /** Authoritative compute-on-read balance (prepayment + penalty aware). Owner-or-staff. */
    @GetMapping("/{loanId}/outstanding")
    public ApiResponse<OutstandingView> outstanding(
            @PathVariable Long loanId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        Loan loan = repaymentService.requireReadableLoan(loanId);
        return ApiResponse.ok(view(loanId, asOf != null ? asOf : LocalDate.now(),
                repaymentService.outstandingBreakdownAsOf(loan, asOf)));
    }

    /**
     * The three balances the repay screen quotes at once: as of today, on the salary day the loan is
     * due, and on the one grace day after it.
     *
     * <p>The borrower's repay page showed these as three separate {@code /outstanding?asOf=} calls,
     * so opening it cost three round trips (and three recomputes) for one screen. This answers all
     * three from a single loan read. Every figure is computed by the same
     * {@code outstandingBreakdownAsOf} the single-date endpoint uses, so the numbers are identical —
     * this changes how many requests it takes to learn them, never what they are.
     *
     * <p>{@code due} and {@code grace} are null when the loan has no due date yet.
     */
    @GetMapping("/{loanId}/outstanding/schedule")
    public ApiResponse<OutstandingScheduleView> outstandingSchedule(
            @PathVariable Long loanId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        Loan loan = repaymentService.requireReadableLoan(loanId);
        LocalDate due = loan.getDueDate();
        // One calendar day after the salary day — the same arithmetic the repay page applied to the
        // due date before it asked for this figure.
        LocalDate grace = due != null ? due.plusDays(1) : null;
        // `asOf` is handed to the service AS GIVEN, null included: a null means "the ledger's today",
        // which the service resolves in IST. Resolving it here with LocalDate.now() instead would
        // read the JVM's zone (UTC in ECS) and quote the borrower yesterday's balance for the 5.5
        // hours each day the two calendars disagree — a real rupee difference on the screen they pay
        // from. This is what keeps the figure identical to /outstanding with no asOf.
        return ApiResponse.ok(new OutstandingScheduleView(
                view(loanId, asOf != null ? asOf : LocalDate.now(),
                        repaymentService.outstandingBreakdownAsOf(loan, asOf)),
                due == null ? null : view(loanId, due, repaymentService.outstandingBreakdownAsOf(loan, due)),
                grace == null ? null : view(loanId, grace, repaymentService.outstandingBreakdownAsOf(loan, grace))));
    }

    private static OutstandingView view(Long loanId, LocalDate asOf,
                                        RepaymentService.OutstandingBreakdown b) {
        return new OutstandingView(loanId, asOf, b.outstandingPaise(), b.settledAmountPaise(),
                b.interestPaise(), b.penaltyPaise(), b.verifiedPaise(), b.interestDays(), b.penaltyDays());
    }
}
