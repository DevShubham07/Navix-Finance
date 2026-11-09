package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.Payment;
import com.navix.loan.entity.RepaymentDiscountOffer;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.repository.RepaymentDiscountOfferRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolution and one-shot consumption of {@link RepaymentDiscountOffer}s (V95).
 *
 * <p>An offer is <b>unlocked</b> when its loan is CLOSED and the latest VERIFIED payment landed on or
 * before {@code repay_by}. It is <b>pinned</b> onto a new application by
 * {@code ApplicationFlowService.reborrow} (the only site) and <b>consumed</b> by
 * {@code LoanService.disburse} with a compare-and-set, so one offer can never discount two loans.
 */
@Component
@RequiredArgsConstructor
public class RepaymentDiscountOffers {

    private final RepaymentDiscountOfferRepository offerRepository;
    private final LoanRepository loanRepository;
    private final PaymentRepository paymentRepository;

    /** The best unlocked, unused offer for the customer: highest percent, then newest. */
    public Optional<RepaymentDiscountOffer> bestFor(Long customerId) {
        return offerRepository.findByCustomerIdAndUsedApplicationIdIsNull(customerId).stream()
                .filter(o -> loanRepository.findById(o.getLoanId())
                        .map(l -> qualifies(o, l, paymentRepository.findByLoanId(l.getId())))
                        .orElse(false))
                .max(Comparator.comparingInt((RepaymentDiscountOffer o) -> o.getPercent())
                        .thenComparing(RepaymentDiscountOffer::getId));
    }

    /** Unused, loan CLOSED, and the latest VERIFIED payment is on or before the deadline. */
    static boolean qualifies(RepaymentDiscountOffer offer, Loan loan, List<Payment> payments) {
        if (offer.getUsedApplicationId() != null || loan.getStatus() != LoanStatus.CLOSED) {
            return false;
        }
        return payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.VERIFIED && p.getPaidOn() != null)
                .map(Payment::getPaidOn)
                .max(Comparator.<LocalDate>naturalOrder())
                .map(last -> !last.isAfter(offer.getRepayBy()))
                .orElse(false);
    }

    /** Pin the best offer (if any) onto the application's two discount columns. */
    public void pin(com.navix.loan.entity.LoanApplication app) {
        bestFor(app.getCustomerId()).ifPresent(o -> {
            app.setFeeDiscountPercent(o.getPercent());
            app.setFeeDiscountOfferId(o.getId());
        });
    }

    /**
     * Mark the application's pinned offer used by this loan. Fails closed: if the offer was already
     * consumed the disbursal is refused rather than minting a second discounted loan whose Key Fact
     * Statement promised a discount.
     */
    public void consume(com.navix.loan.entity.LoanApplication app, Long loanId) {
        if (app.getFeeDiscountOfferId() == null) {
            return;
        }
        if (offerRepository.claim(app.getFeeDiscountOfferId(), app.getId(), loanId, Instant.now()) != 1) {
            throw new BusinessException("DISCOUNT_OFFER_USED",
                    "The repayment discount on this application was already used on another loan");
        }
    }
}
