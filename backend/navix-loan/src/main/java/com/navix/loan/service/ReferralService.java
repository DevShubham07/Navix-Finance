package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.notification.event.ReferralPayoutCreatedEvent;
import com.navix.common.notification.event.ReferralRewardCreditedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.config.ReferralProperties;
import com.navix.loan.domain.ReferralBeneficiaryRole;
import com.navix.loan.domain.ReferralPayoutStatus;
import com.navix.loan.domain.ReferralStatus;
import com.navix.loan.dto.ReferralDtos.ApplyCodeResult;
import com.navix.loan.dto.ReferralDtos.ExpenseSummaryView;
import com.navix.loan.dto.ReferralDtos.MyReferralView;
import com.navix.loan.dto.ReferralDtos.PayoutView;
import com.navix.loan.dto.ReferralDtos.ValidateCodeView;
import com.navix.loan.entity.Referral;
import com.navix.loan.entity.ReferralCode;
import com.navix.loan.entity.ReferralPayout;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.ReferralCodeRepository;
import com.navix.loan.repository.ReferralPayoutRepository;
import com.navix.loan.repository.ReferralRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The refer-a-friend program. A borrower gets a unique shareable {@link ReferralCode}; a new borrower
 * redeems a code at signup ({@link #applyCode}), creating a PENDING {@link Referral}. When the referred
 * borrower's first loan is disbursed, {@link #onLoanDisbursed} (called in-band from
 * {@code ApplicationFlowService.finalizeDisbursal}) qualifies the referral and creates two PENDING
 * {@link ReferralPayout} rows (referrer + referred), each worth {@code navix.referral.reward-paise}.
 * The Disbursement Head settles each manually ({@link #payPayout}), logging a transaction id, which
 * credits the beneficiary (notification) and feeds the separate referral-expense view.
 *
 * <p>Reward grant is atomic with the disbursement (same transaction); the two user-facing
 * notifications are event-driven (AFTER_COMMIT, async) — business code never blocks on delivery.
 */
@Service
@RequiredArgsConstructor
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    /** Code alphabet — uppercase, ambiguous glyphs (0/O/1/I/L) dropped for easy sharing. */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 8;
    private static final int CODE_MAX_TRIES = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReferralCodeRepository codeRepository;
    private final ReferralRepository referralRepository;
    private final ReferralPayoutRepository payoutRepository;
    private final LoanRepository loanRepository;
    private final ApplicantReviewService reviewService;
    private final ReferralProperties properties;
    private final FeatureFlagService featureFlags;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Whether the referral program is on. The dev-controlled {@code feature_flag} row ("referral") is the
     * live source of truth; when no row exists it falls back to the static {@code navix.referral.enabled}
     * property, so behaviour is unchanged until a developer touches the table. A SQL update flips this on
     * the next call, no redeploy.
     */
    private boolean referralEnabled() {
        return featureFlags.isEnabled("referral", properties.enabled());
    }

    // ---- borrower ------------------------------------------------------------------

    /** The calling borrower's referral panel — their code (lazily minted) + reward + earnings. */
    @Transactional
    public MyReferralView myReferral() {
        Long applicantId = currentBorrowerId();
        String code = getOrCreateCode(applicantId).getCode();
        long qualified = referralRepository.countByReferrerApplicantIdAndStatus(
                applicantId, ReferralStatus.QUALIFIED);
        long earned = 0L;
        long pending = 0L;
        for (ReferralPayout p : payoutRepository.findByBeneficiaryApplicantId(applicantId)) {
            if (p.getStatus() == ReferralPayoutStatus.PAID) {
                earned += p.getAmountPaise();
            } else {
                pending += p.getAmountPaise();
            }
        }
        return new MyReferralView(referralEnabled(), code, properties.rewardPaise(),
                properties.rewardRupees(), shareMessage(code), qualified, earned, pending);
    }

    /** Borrower redeems a referral code at signup. Guards: enabled, code exists, not self, not already
     *  referred, and the caller is a genuinely new borrower (no prior loan). */
    @Transactional
    public ApplyCodeResult applyCode(String rawCode) {
        Long applicantId = currentBorrowerId();
        if (!referralEnabled()) {
            throw new BusinessException("REFERRAL_DISABLED", "The referral program is not available right now.");
        }
        String code = normalizeCode(rawCode);
        if (code == null) {
            throw new BusinessException("INVALID_REFERRAL_CODE", "Enter a valid referral code.");
        }
        ReferralCode referrerCode = codeRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException("INVALID_REFERRAL_CODE",
                        "That referral code doesn't exist."));
        Long referrerId = referrerCode.getApplicantId();
        if (referrerId.equals(applicantId)) {
            throw new BusinessException("SELF_REFERRAL", "You can't use your own referral code.");
        }
        if (referralRepository.findByReferredApplicantId(applicantId).isPresent()) {
            throw new BusinessException("ALREADY_REFERRED", "You've already applied a referral code.");
        }
        if (!loanRepository.findByApplicantId(applicantId).isEmpty()) {
            throw new BusinessException("NOT_NEW_BORROWER",
                    "Referral codes apply to your first NAVIX loan only.");
        }
        Referral referral = new Referral();
        referral.setReferrerApplicantId(referrerId);
        referral.setReferredApplicantId(applicantId);
        referral.setCodeUsed(code);
        referral.setStatus(ReferralStatus.PENDING);
        referralRepository.save(referral);
        String referrerName = nameOf(referrerId);
        return new ApplyCodeResult(true,
                "Referral applied — you and " + (referrerName != null ? referrerName : "your friend")
                        + " each get ₹" + properties.rewardRupees() + " once your first loan is disbursed.",
                referrerName, properties.rewardPaise());
    }

    /** Lenient preview of a code for live signup feedback. Never throws. */
    @Transactional(readOnly = true)
    public ValidateCodeView validate(String rawCode) {
        Long applicantId = currentBorrowerId();
        if (!referralEnabled()) {
            return new ValidateCodeView(false, null, properties.rewardPaise(),
                    "The referral program is not available right now.");
        }
        String code = normalizeCode(rawCode);
        if (code == null) {
            return new ValidateCodeView(false, null, properties.rewardPaise(), "Enter a referral code.");
        }
        Optional<ReferralCode> match = codeRepository.findByCode(code);
        if (match.isEmpty()) {
            return new ValidateCodeView(false, null, properties.rewardPaise(),
                    "That referral code doesn't exist.");
        }
        if (match.get().getApplicantId().equals(applicantId)) {
            return new ValidateCodeView(false, null, properties.rewardPaise(),
                    "You can't use your own referral code.");
        }
        String referrerName = nameOf(match.get().getApplicantId());
        return new ValidateCodeView(true, referrerName, properties.rewardPaise(),
                "You and " + (referrerName != null ? referrerName : "your friend")
                        + " each get ₹" + properties.rewardRupees() + " once your first loan is disbursed.");
    }

    // ---- reward trigger (called in-band at disbursement) ---------------------------

    /**
     * Grant the referral reward when the referred borrower's first loan is disbursed. Idempotent and a
     * clean no-op unless the program is enabled AND a still-PENDING referral names this borrower as the
     * referred party (so a reborrow / a borrower with no referral does nothing). Runs inside the
     * disbursement transaction — the referral + two payouts commit atomically with the loan.
     */
    @Transactional
    public void onLoanDisbursed(Long referredApplicantId, Long loanId) {
        if (!referralEnabled() || referredApplicantId == null) {
            return;
        }
        Optional<Referral> match = referralRepository.findByReferredApplicantId(referredApplicantId);
        if (match.isEmpty() || match.get().getStatus() != ReferralStatus.PENDING) {
            return;
        }
        Referral referral = match.get();
        Long referrerId = referral.getReferrerApplicantId();
        referral.setStatus(ReferralStatus.QUALIFIED);
        referral.setQualifyingLoanId(loanId);
        referral.setQualifiedAt(Instant.now());
        referralRepository.save(referral);

        long reward = properties.rewardPaise();
        createPayout(referral.getId(), referrerId, ReferralBeneficiaryRole.REFERRER, referredApplicantId, reward, loanId);
        createPayout(referral.getId(), referredApplicantId, ReferralBeneficiaryRole.REFERRED, referrerId, reward, loanId);

        eventPublisher.publishEvent(new ReferralPayoutCreatedEvent(
                referral.getId(), referrerId, referredApplicantId, loanId, reward, Instant.now()));
        log.info("referral {} qualified by loan {} — two payouts of {} paise created", referral.getId(), loanId, reward);
    }

    private void createPayout(Long referralId, Long beneficiaryId, ReferralBeneficiaryRole role,
                              Long counterpartyId, long amountPaise, Long loanId) {
        ReferralPayout payout = new ReferralPayout();
        payout.setReferralId(referralId);
        payout.setBeneficiaryApplicantId(beneficiaryId);
        payout.setBeneficiaryRole(role);
        payout.setCounterpartyApplicantId(counterpartyId);
        payout.setAmountPaise(amountPaise);
        payout.setStatus(ReferralPayoutStatus.PENDING);
        payout.setQualifyingLoanId(loanId);
        payoutRepository.save(payout);
    }

    // ---- staff (Disbursement Head / Admin) -----------------------------------------

    /** Payouts for the approval queue / expense view. {@code status} null → all (newest first). */
    @Transactional(readOnly = true)
    public List<PayoutView> listPayouts(ReferralPayoutStatus status) {
        requireDisbursementHead();
        requireReferralEnabled();
        List<ReferralPayout> payouts = status != null
                ? payoutRepository.findByStatusOrderByIdAsc(status)
                : payoutRepository.findAllByOrderByIdDesc();
        Map<Long, String> names = new HashMap<>();
        return payouts.stream()
                .map(p -> PayoutView.of(p,
                        names.computeIfAbsent(p.getBeneficiaryApplicantId(), this::nameOf),
                        p.getCounterpartyApplicantId() == null ? null
                                : names.computeIfAbsent(p.getCounterpartyApplicantId(), this::nameOf)))
                .toList();
    }

    /** Mark a payout paid, recording the logged transaction id — credits the beneficiary (notification). */
    @Transactional
    public PayoutView payPayout(Long id, String txnRef) {
        requireDisbursementHead();
        requireReferralEnabled();
        if (txnRef == null || txnRef.isBlank()) {
            throw new BusinessException("MISSING_TXN_REF", "A transaction id is required to mark a payout paid.");
        }
        ReferralPayout payout = payoutRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ReferralPayout", String.valueOf(id)));
        if (payout.getStatus() == ReferralPayoutStatus.PAID) {
            throw new BusinessException("ALREADY_PAID", "This payout has already been paid.");
        }
        payout.setStatus(ReferralPayoutStatus.PAID);
        payout.setTxnRef(txnRef.trim());
        payout.setPaidAt(Instant.now());
        payout.setPaidBy(ActorContext.get().name());
        payoutRepository.save(payout);

        eventPublisher.publishEvent(new ReferralRewardCreditedEvent(
                payout.getId(), payout.getBeneficiaryApplicantId(), payout.getCounterpartyApplicantId(),
                payout.getBeneficiaryRole().name(), payout.getAmountPaise(), payout.getTxnRef(), Instant.now()));
        return PayoutView.of(payout, nameOf(payout.getBeneficiaryApplicantId()),
                payout.getCounterpartyApplicantId() == null ? null : nameOf(payout.getCounterpartyApplicantId()));
    }

    /** Totals for the separate referral-expense dashboard. */
    @Transactional(readOnly = true)
    public ExpenseSummaryView expenseSummary() {
        requireDisbursementHead();
        requireReferralEnabled();
        long pendingCount = 0;
        long pendingPaise = 0;
        long paidCount = 0;
        long paidPaise = 0;
        for (ReferralPayout p : payoutRepository.findAll()) {
            if (p.getStatus() == ReferralPayoutStatus.PAID) {
                paidCount++;
                paidPaise += p.getAmountPaise();
            } else {
                pendingCount++;
                pendingPaise += p.getAmountPaise();
            }
        }
        return new ExpenseSummaryView(pendingCount, pendingPaise, paidCount, paidPaise,
                pendingCount + paidCount, pendingPaise + paidPaise);
    }

    // ---- internals -----------------------------------------------------------------

    private ReferralCode getOrCreateCode(Long applicantId) {
        return codeRepository.findByApplicantId(applicantId).orElseGet(() -> {
            ReferralCode rc = new ReferralCode();
            rc.setApplicantId(applicantId);
            rc.setCode(generateUniqueCode());
            return codeRepository.save(rc);
        });
    }

    private String generateUniqueCode() {
        for (int i = 0; i < CODE_MAX_TRIES; i++) {
            String code = randomCode();
            if (!codeRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new BusinessException("REFERRAL_CODE_GEN_FAILED", "Could not generate a referral code, try again.");
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static String normalizeCode(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toUpperCase();
        return t.isEmpty() ? null : t;
    }

    private String shareMessage(String code) {
        return "Use my NAVIX referral code " + code + " when you sign up — you and I each get ₹"
                + properties.rewardRupees() + " once your first loan is disbursed.";
    }

    private String nameOf(Long applicantId) {
        if (applicantId == null) {
            return null;
        }
        return reviewService.latestProfile(applicantId).map(p -> p.getFullName()).orElse(null);
    }

    /** Borrower-scoped read: resolves the caller's applicant id from the JWT principal. */
    private Long currentBorrowerId() {
        CurrentActor actor = ActorContext.get();
        if (!"BORROWER".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role BORROWER");
        }
        return Long.valueOf(actor.id());
    }

    /** Full kill-switch gate: when the referral flag is off, staff payout management is disabled too. */
    private void requireReferralEnabled() {
        if (!referralEnabled()) {
            throw new BusinessException("REFERRAL_DISABLED", "The referral program is currently disabled.");
        }
    }

    /** Staff gate for payout management — the Disbursement Head, or ADMIN (oversight). */
    private void requireDisbursementHead() {
        CurrentActor actor = ActorContext.get();
        if (!"DISBURSEMENT_HEAD".equals(actor.role()) && !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role DISBURSEMENT_HEAD");
        }
    }
}
