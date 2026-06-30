package com.navix.loan.dto;

import com.navix.loan.entity.ReferralPayout;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * Request/response payloads for the refer-a-friend program. Money is integer paise. Views are mapped
 * from entities via static {@code of(...)} factories (mirrors the module convention); name fields are
 * resolved in {@code ReferralService} (they are not on the entity).
 */
public final class ReferralDtos {

    private ReferralDtos() {
    }

    // ---- borrower ------------------------------------------------------------------

    /** The borrower's own referral panel: their code, the reward, share copy, and earnings roll-up. */
    public record MyReferralView(
            boolean enabled,
            String code,
            long rewardPaise,
            long rewardRupees,
            String shareMessage,
            long referredQualifiedCount,
            long totalEarnedPaise,
            long pendingPaise
    ) {
    }

    /** Borrower applies a referral code to themselves at signup. */
    public record ApplyCodeRequest(@NotBlank String code) {
    }

    /** Outcome of applying a code (the happy path; guard failures throw a BusinessException). */
    public record ApplyCodeResult(boolean accepted, String message, String referrerName, long rewardPaise) {
    }

    /** Lenient code preview for live signup feedback (never throws; {@code valid=false} on any issue). */
    public record ValidateCodeView(boolean valid, String referrerName, long rewardPaise, String message) {
    }

    // ---- staff (Disbursement Head / Admin) -----------------------------------------

    /** One reward payout row for the approval dashboard / referral-expense view. */
    public record PayoutView(
            Long id,
            Long referralId,
            Long beneficiaryApplicantId,
            String beneficiaryName,
            String beneficiaryRole,
            Long counterpartyApplicantId,
            String counterpartyName,
            long amountPaise,
            String status,
            String txnRef,
            Instant paidAt,
            String paidBy,
            Long qualifyingLoanId,
            Instant createdAt
    ) {

        /** Map an entity, supplying the resolved beneficiary/counterparty display names. */
        public static PayoutView of(ReferralPayout p, String beneficiaryName, String counterpartyName) {
            return new PayoutView(
                    p.getId(), p.getReferralId(),
                    p.getBeneficiaryApplicantId(), beneficiaryName, p.getBeneficiaryRole().name(),
                    p.getCounterpartyApplicantId(), counterpartyName,
                    p.getAmountPaise(), p.getStatus().name(), p.getTxnRef(),
                    p.getPaidAt(), p.getPaidBy(), p.getQualifyingLoanId(), p.getCreatedAt());
        }
    }

    /** Disbursement Head marks a payout paid, logging the bank/UPI transaction id. */
    public record PayPayoutRequest(@NotBlank String txnRef) {
    }

    /** Totals for the separate referral-expense dashboard. */
    public record ExpenseSummaryView(
            long pendingCount,
            long pendingPaise,
            long paidCount,
            long paidPaise,
            long totalCount,
            long totalPaise
    ) {
    }
}
