package com.navix.loan.payu;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.loan.service.TransactionService;
import com.navix.loan.service.TransactionService.BorrowerRef;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff queue behind {@code payu_order.review_reason}: money PayU captured that was not credited, needs a
 * refund, or looks wrong. ACCOUNTANT and ADMIN only. Resolving records who dealt with a row and why; it never
 * credits, refunds or edits the order (refunds are made in the PayU dashboard).
 */
@Service
@RequiredArgsConstructor
public class PayuReviewService {

    /** One bounded page: the queue is exception-only, so this is far more than will ever be open. */
    static final int LIMIT = 200;

    public record ReviewView(Long orderId, String txnid, Long loanId, Long customerId, String customerName,
                             long amountPaise, Long feePaise, String mihpayid, String bankRefNum, String mode,
                             LocalDate paidOn, String status, String reviewReason, boolean overpayment,
                             boolean credited, Instant createdAt) {
    }

    private final PayuOrderRepository orders;
    private final TransactionService transactions;

    @Transactional(readOnly = true)
    public List<ReviewView> listUnresolved() {
        requireStaff();
        List<PayuOrder> rows = orders.findUnresolvedReview(PageRequest.of(0, LIMIT));
        Map<Long, BorrowerRef> byLoan = transactions.borrowersByLoanId(
                rows.stream().map(PayuOrder::getLoanId).distinct().toList());
        return rows.stream().map(o -> {
            BorrowerRef ref = byLoan.get(o.getLoanId());
            return view(o, ref != null ? ref.borrowerName() : null);
        }).toList();
    }

    /** Idempotent: a second resolve keeps the first resolver, time and note. */
    @Transactional
    public void resolve(Long orderId, String note) {
        requireStaff();
        String trimmed = note == null ? "" : note.strip();
        if (trimmed.isEmpty()) {
            throw new BusinessException("REVIEW_NOTE_REQUIRED", "Say what was done about this payment");
        }
        PayuOrder o = orders.lockById(orderId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found"));
        if (o.getReviewReason() == null) {
            throw new BusinessException("ORDER_NOT_IN_REVIEW", "This order is not in the review queue");
        }
        if (o.getReviewResolvedAt() != null) {
            return;
        }
        o.setReviewResolvedAt(Instant.now());
        o.setReviewResolvedBy(Long.valueOf(ActorContext.get().id()));
        o.setReviewNote(trimmed);
        orders.save(o);
    }

    static ReviewView view(PayuOrder o, String customerName) {
        return new ReviewView(o.getId(), o.getTxnid(), o.getLoanId(), o.getCustomerId(), customerName,
                o.getAmountPaise(), o.getFeePaise(), o.getMihpayid(), o.getBankRefNum(), o.getMode(), o.getPaidOn(),
                o.getStatus().name(), o.getReviewReason(), o.isOverpayment(), o.getPaymentId() != null,
                o.getCreatedAt());
    }

    /** Allowlist, so DSA and every other staff role (and borrowers) are refused. */
    private static void requireStaff() {
        String role = ActorContext.get().role();
        if (!"ACCOUNTANT".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ACCOUNTANT or ADMIN");
        }
    }
}
