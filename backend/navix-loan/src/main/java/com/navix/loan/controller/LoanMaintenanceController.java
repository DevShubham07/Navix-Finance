package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.web.ApiResponse;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.RepaymentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only ledger maintenance. {@code /api/admin/**} is gated to {@code ROLE_STAFF} in
 * {@code SecurityConfig}; the real ADMIN check lives here, exactly like {@link BureauBackfillController}.
 *
 * <p>Exists for one job: repairing loans whose cached balance was computed by the old
 * verification-date logic, which charged interest for the accountant's lag (see
 * {@code RepaymentService.recomputeOutstanding}). Recomputing is idempotent and compute-on-read, so
 * this is a re-runnable endpoint rather than a Flyway migration — a data migration that half-fails
 * cannot be retried, and this one may need to be run again after any correction.
 */
@RestController
@RequestMapping("/api/admin/loans")
@RequiredArgsConstructor
public class LoanMaintenanceController {

    /** Loans whose balance is still moving. A CLOSED loan's history is deliberately left alone. */
    private static final List<LoanStatus> OPEN =
            List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE, LoanStatus.IN_COLLECTIONS);

    private final RepaymentService repaymentService;
    private final LoanRepository loanRepository;

    /**
     * Recompute every open loan's balance on the corrected math.
     *
     * <p><b>Dry run by default.</b> A live pass can close a batch of loans at once, and each closure
     * fires LOAN_CLOSED on in-app + SMS + email — an accidental burst of "your loan is closed"
     * messages to borrowers is not undoable. So the operator sees {@code wouldClose} first and opts
     * in with {@code apply=true}.
     */
    @PostMapping("/recompute-outstanding")
    public ApiResponse<RecomputeSummary> recomputeOutstanding(
            @RequestParam(defaultValue = "false") boolean apply) {
        requireAdmin();
        List<Loan> open = loanRepository.findByStatusIn(OPEN);
        long wouldClose = 0;
        long closed = 0;
        long changed = 0;
        for (Loan loan : open) {
            long before = loan.getOutstanding() == null ? 0L : loan.getOutstanding();
            long after = repaymentService.settlementBalance(loan.getId());
            if (after == 0L) {
                wouldClose++;
            }
            if (after != before) {
                changed++;
            }
            if (apply) {
                repaymentService.recomputeOutstanding(loan.getId());
                if (loanRepository.findById(loan.getId())
                        .map(l -> l.getStatus() == LoanStatus.CLOSED).orElse(false)) {
                    closed++;
                }
            }
        }
        return ApiResponse.ok(new RecomputeSummary(apply, open.size(), changed, wouldClose, closed));
    }

    /**
     * @param applied     false for a dry run — nothing was written
     * @param scanned     open loans examined
     * @param balanceDrift loans whose recomputed balance differs from the cached one
     * @param wouldClose  loans that the corrected math settles at zero
     * @param closed      loans actually closed (always 0 on a dry run)
     */
    public record RecomputeSummary(boolean applied, int scanned, long balanceDrift,
                                   long wouldClose, long closed) {
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "ADMIN required");
        }
    }
}
