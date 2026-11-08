package com.navix.loan.payu;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.Payment;
import com.navix.loan.payu.PayuClient.VerifyResult;
import com.navix.loan.payu.PayuOrder.Status;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.service.CustomerReviewService;
import com.navix.loan.service.RepaymentService;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PayU Hosted Checkout for loan repayments. A real-money path, so three rules carry it:
 * <ol>
 *   <li><b>Nothing the browser or a webhook says is trusted.</b> Every callback only triggers
 *       {@link #reconcile}, which asks PayU ({@code verify_payment}) and credits from THAT answer.</li>
 *   <li><b>Credit once.</b> The order row is locked while the answer is applied; the credit's transaction
 *       reference is PayU's {@code mihpayid}, which {@code RepaymentService.recordPayment} dedupes per loan,
 *       so even a lost commit cannot double-credit.</li>
 *   <li><b>SUCCESS is never downgraded.</b> A FAILED order may still become SUCCESS on a later verify.</li>
 * </ol>
 * Deliberately NOT {@code @Transactional}: the provider call must sit between transactions, and the credit
 * runs in its own so a caught {@code LOAN_SETTLED} cannot poison the order update (same shape as DebitService).
 */
@Slf4j
@Service
public class PayuService {

    static final String FLAG = "payu-repayment";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<Status> OPEN = EnumSet.of(Status.INITIATED, Status.PENDING);
    private static final Duration IN_PROGRESS_WINDOW = Duration.ofMinutes(15);
    private static final Duration ABANDONED_AFTER = Duration.ofMinutes(30);
    private static final Duration EXPIRY = Duration.ofHours(24);
    private static final Duration POLL_AFTER = Duration.ofSeconds(10);
    private static final int SWEEP_BATCH = 50;
    private static final long MIN_AMOUNT_PAISE = 100;
    private static final String PRODUCT_INFO = "DhanBoost loan repayment";
    private static final Pattern SAFE_EMAIL = Pattern.compile("^[A-Za-z0-9._@/-]{3,50}$");
    private static final SecureRandom RNG = new SecureRandom();

    private final PayuProperties props;
    private final PayuClient client;
    private final PayuOrderRepository orders;
    private final PaymentRepository payments;
    private final RepaymentService repayments;
    private final CustomerReviewService reviews;
    private final FeatureFlagService flags;
    private final TransactionTemplate tx;
    private final PayuCardFee cardFee;

    public PayuService(PayuProperties props, PayuClient client, PayuOrderRepository orders,
                       PaymentRepository payments, RepaymentService repayments, CustomerReviewService reviews,
                       FeatureFlagService flags, TransactionTemplate tx) {
        this.props = props;
        this.client = client;
        this.orders = orders;
        this.payments = payments;
        this.repayments = repayments;
        this.reviews = reviews;
        this.flags = flags;
        this.tx = tx;
        this.cardFee = new PayuCardFee(props.cardFeeBps(), props.cardFeeGstBps());
    }

    // ---- views ----------------------------------------------------------------------------------------

    /** What the borrower may see. No review reason, no provider message. */
    public record OrderView(String txnid, String status, long amountPaise, Long feePaise, String mode,
                            String bankRefNum, boolean credited, Instant createdAt) {
    }

    public record State(boolean enabled, boolean manualAllowed, Long creditCardFeePaise, OrderView openOrder,
                        OrderView lastOrder) {
    }

    public record Checkout(String action, Map<String, String> fields, OrderView order) {
    }

    static OrderView view(PayuOrder o) {
        return new OrderView(o.getTxnid(), o.getStatus().name(), o.getAmountPaise(), o.getFeePaise(), o.getMode(),
                o.getBankRefNum(), o.getPaymentId() != null, o.getCreatedAt());
    }

    /** Feature flag AND credentials. A disabled feature must never block a deploy or a borrower. */
    public boolean enabled() {
        return flags.isEnabled(FLAG, false) && props.credentialsSet() && props.hostsAllowed();
    }

    /**
     * Whether the borrower may use the manual screenshot flow: always when PayU is off; otherwise only after
     * their most recent PayU attempt on this loan was unsuccessful. An attempt that never left PayU's page
     * (INITIATED) counts as unsuccessful after {@link #ABANDONED_AFTER}: PayU can report those as open for
     * hours, and a closed tab or a PayU outage must not leave the borrower with no way to pay.
     */
    public boolean manualAllowed(Long loanId) {
        if (!enabled()) {
            return true;
        }
        Optional<PayuOrder> newest = orders.findFirstByLoanIdOrderByCreatedAtDescIdDesc(loanId);
        if (newest.isEmpty()) {
            return false;
        }
        Long newestId = newest.get().getId();
        Instant cutoff = Instant.now().minus(ABANDONED_AFTER);
        // Our copy of an INITIATED row can be up to an hour old, and the borrower may have reached the bank
        // since. Ask PayU first, or manual would open beside a live payment and the loan would be paid twice.
        // An unreachable PayU leaves it INITIATED, which is the outage case manual is there for.
        for (PayuOrder o : orders.findByLoanIdAndStatusInOrderByCreatedAtDescIdDesc(loanId, OPEN)) {
            if (o.getStatus() == Status.INITIATED && !stillOnPayuPage(o, newestId, cutoff)
                    && (o.getLastCheckedAt() == null
                        || o.getLastCheckedAt().isBefore(Instant.now().minus(POLL_AFTER)))) {
                reconcile(o.getTxnid());
            }
        }
        Optional<PayuOrder> latest = orders.findFirstByLoanIdOrderByCreatedAtDescIdDesc(loanId);
        boolean anyLive = orders.findByLoanIdAndStatusInOrderByCreatedAtDescIdDesc(loanId, OPEN).stream()
                .anyMatch(o -> o.getStatus() != Status.INITIATED || stillOnPayuPage(o, newestId, cutoff));
        if (anyLive) {
            return false;
        }
        Status s = latest.get().getStatus();
        return s == Status.FAILED || s == Status.EXPIRED || s == Status.INITIATED;
    }

    /** Only the borrower's newest attempt, while it is recent, can still be in use on PayU's page. */
    private static boolean stillOnPayuPage(PayuOrder o, Long newestId, Instant cutoff) {
        return o.getId().equals(newestId) && (o.getCreatedAt() == null || o.getCreatedAt().isAfter(cutoff));
    }

    public State state(Long loanId, Long amountPaise) {
        requireBorrowerOwns(loanId);
        Long fee = amountPaise != null && amountPaise > 0 ? cardFee.displayFeePaise(amountPaise) : null;
        OrderView open = orders.findByLoanIdAndStatusInOrderByCreatedAtDescIdDesc(loanId, OPEN).stream()
                .findFirst().map(PayuService::view).orElse(null);
        OrderView last = orders.findFirstByLoanIdOrderByCreatedAtDescIdDesc(loanId).map(PayuService::view).orElse(null);
        return new State(enabled(), manualAllowed(loanId), fee, open, last);
    }

    /** One order of this loan; an open order not checked for 10 s is re-read from PayU first. */
    public OrderView order(Long loanId, String txnid) {
        requireBorrowerOwns(loanId);
        PayuOrder o = orders.findByTxnid(txnid).filter(x -> x.getLoanId().equals(loanId))
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found"));
        if (o.open() && (o.getLastCheckedAt() == null
                || o.getLastCheckedAt().isBefore(Instant.now().minus(POLL_AFTER)))) {
            PayuOrder fresh = reconcile(txnid);
            if (fresh != null) {
                o = fresh;
            }
        }
        return view(o);
    }

    // ---- create ---------------------------------------------------------------------------------------

    public Checkout createOrder(Long loanId, long amountPaise) {
        Loan loan = requireBorrowerOwns(loanId);
        if (!enabled()) {
            throw new BusinessException("PAYU_DISABLED", "Online payment is not available right now");
        }
        if (loan.getStatus() == LoanStatus.CLOSED || loan.getStatus() == LoanStatus.REPAID) {
            throw new BusinessException("LOAN_SETTLED", "Loan is already settled");
        }
        if (amountPaise < MIN_AMOUNT_PAISE) {
            throw new BusinessException("INVALID_AMOUNT", "The minimum online payment is Rs. 1");
        }
        if (payments.findByLoanId(loanId).stream().anyMatch(p -> p.getStatus() == PaymentStatus.PENDING_VERIFICATION)) {
            throw new BusinessException("MANUAL_PAYMENT_PENDING",
                    "A payment you submitted is still being verified");
        }
        // Ask PayU about anything still open BEFORE starting another charge (outside any transaction).
        for (PayuOrder open : orders.findByLoanIdAndStatusInOrderByCreatedAtDescIdDesc(loanId, OPEN)) {
            PayuOrder after = reconcile(open.getTxnid());
            if (after == null) {
                continue;
            }
            if (after.getStatus() == Status.SUCCESS) {
                throw new BusinessException("PAYU_PAYMENT_RECEIVED", "Your payment has just been received");
            }
            if (after.getStatus() == Status.PENDING
                    && after.getCreatedAt().isAfter(Instant.now().minus(IN_PROGRESS_WINDOW))) {
                throw new BusinessException("PAYU_IN_PROGRESS", "A payment is still being confirmed");
            }
        }
        long owed = repayments.outstandingAsOf(loanId, LocalDate.now(IST));
        if (owed <= 0) {
            throw new BusinessException("LOAN_SETTLED", "Loan is already settled");
        }
        if (amountPaise > owed) {
            throw new BusinessException("AMOUNT_EXCEEDS_OUTSTANDING", "The amount is more than is owed");
        }

        CustomerProfile profile = reviews.latestProfile(loan.getCustomerId()).orElse(null);
        String email = payuEmail(profile == null ? null : profile.getEmail(), loanId);
        String firstname = sanitiseName(profile == null ? null : profile.getFullName());
        String phone = lastTenDigits(profile == null ? null : profile.getMobile());

        String amount = BigDecimal.valueOf(amountPaise, 2).toPlainString();
        String charges = cardFee.additionalCharges(amountPaise);
        String txnid = "DB" + randomHex(20);
        String udf1 = String.valueOf(loanId);
        String hash = PayuHash.request(props.key(), txnid, amount, PRODUCT_INFO, firstname, email,
                udf1, "", "", "", "", props.salt(), charges);

        PayuOrder row = new PayuOrder();
        row.setTxnid(txnid);
        row.setLoanId(loanId);
        row.setCustomerId(loan.getCustomerId());
        row.setAmountPaise(amountPaise);
        row.setAdditionalCharges(charges);
        row.setStatus(Status.INITIATED);
        row.setNextCheckAt(Instant.now().plus(Duration.ofMinutes(5)));
        PayuOrder saved = tx.execute(s -> orders.save(row));

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", props.key());
        fields.put("txnid", txnid);
        fields.put("amount", amount);
        fields.put("productinfo", PRODUCT_INFO);
        fields.put("firstname", firstname);
        fields.put("email", email);
        fields.put("phone", phone);
        fields.put("surl", props.returnUrl());
        fields.put("furl", props.returnUrl());
        fields.put("curl", props.returnUrl());
        fields.put("udf1", udf1);
        if (charges != null) {
            fields.put("additional_charges", charges);
        }
        fields.put("hash", hash);
        return new Checkout(props.checkoutUrl(), fields, view(saved));
    }

    // ---- callbacks and reconcile ----------------------------------------------------------------------

    /**
     * Webhook or browser return. Only the reverse hash and the txnid are checked here; the body's status and
     * amount are never used. Returns the txnid when the hash was valid, else null.
     */
    public String onCallback(Map<String, String> fields) {
        String txnid = fields == null ? null : fields.get("txnid");
        try {
            if (txnid == null || txnid.isBlank() || orders.findByTxnid(txnid).isEmpty()) {
                log.warn("PayU callback ignored: unknown txnid");
                return null;
            }
            if (!PayuHash.matches(PayuHash.reverse(fields, props.salt()), fields.get("hash"))) {
                log.warn("PayU callback ignored: hash mismatch txnid={}", txnid);
                return null;
            }
            reconcile(txnid);
            return txnid;
        } catch (RuntimeException e) {
            log.warn("PayU callback handling failed txnid={} exception={}", txnid, e.getClass().getSimpleName());
            return null;
        }
    }

    /** Sweep: up to 50 due open orders; one failure never stops the rest. Not gated on the flag. */
    public int reconcileDue() {
        List<PayuOrder> due = orders.findDue(OPEN, Instant.now(), PageRequest.of(0, SWEEP_BATCH));
        int n = 0;
        for (PayuOrder o : due) {
            try {
                reconcile(o.getTxnid());
                n++;
            } catch (RuntimeException e) {
                log.warn("PayU reconcile failed txnid={} exception={}", o.getTxnid(), e.getClass().getSimpleName());
            }
        }
        return n;
    }

    /** Re-read one order from PayU and apply the answer. Safe to repeat. Returns null for an unknown txnid. */
    public PayuOrder reconcile(String txnid) {
        PayuOrder probe = orders.findByTxnid(txnid).orElse(null);
        if (probe == null || done(probe)) {
            return probe;
        }
        Optional<VerifyResult> answer = client.verify(txnid); // OUTSIDE any transaction
        return tx.execute(s -> {
            PayuOrder row = orders.lockByTxnid(txnid).orElse(probe);
            if (done(row)) {
                return row;
            }
            Instant now = Instant.now();
            if (answer.isPresent()) {
                apply(row, answer.get(), now);
            }
            row.setLastCheckedAt(now);
            row.setCheckCount(row.getCheckCount() + 1);
            row.setNextCheckAt(row.open() ? now.plus(row.getCheckCount() == 1
                    ? Duration.ofMinutes(15) : Duration.ofHours(1)) : null);
            return orders.save(row);
        });
    }

    /** Credited, or parked for review (mismatch / settled loan): nothing more to do automatically. */
    private static boolean done(PayuOrder o) {
        return o.getPaymentId() != null || o.getReviewReason() != null;
    }

    private void apply(PayuOrder row, VerifyResult r, Instant now) {
        String st = lower(r.status());
        String um = lower(r.unmappedstatus());
        if ("success".equals(st) && "captured".equals(um)) {
            succeed(row, r);
            return;
        }
        if (row.getStatus() == Status.SUCCESS) {
            return; // never downgraded
        }
        if (r.found() && r.mihpayid() != null) {
            row.setMihpayid(r.mihpayid());
        }
        Status next = row.getStatus();
        if (!r.found()) {
            next = row.getStatus(); // PayU has nothing yet: stays INITIATED
        } else if ("failure".equals(st)) {
            next = Status.FAILED;
            row.setErrorCode(r.errorCode());
        } else if ("success".equals(st)) {
            next = Status.PENDING; // auth: authorised, not captured
        } else if ("pending".equals(st)) {
            next = "initiated".equals(um) ? Status.INITIATED : Status.PENDING;
        }
        boolean finished = row.getStatus() == Status.FAILED || row.getStatus() == Status.EXPIRED;
        if (!(finished && (next == Status.INITIATED || next == Status.PENDING))) {
            row.setStatus(next);
        }
        if (row.open() && row.getCreatedAt() != null && row.getCreatedAt().isBefore(now.minus(EXPIRY))) {
            row.setStatus(Status.EXPIRED);
        }
    }

    private void succeed(PayuOrder row, VerifyResult r) {
        row.setStatus(Status.SUCCESS);
        row.setMihpayid(r.mihpayid());
        row.setMode(r.mode());
        row.setBankcode(r.bankcode());
        row.setBankRefNum(r.bankRefNum());
        row.setErrorCode(null);
        row.setPaidOn(paidOn(r.addedon()));

        Long amount = paise(r.transactionAmount());
        if (amount == null || amount != row.getAmountPaise()) {
            row.setReviewReason("AMOUNT_MISMATCH"); // money arrived for a different amount: a human decides
            log.warn("PayU amount mismatch txnid={} loanId={}", row.getTxnid(), row.getLoanId());
            return;
        }
        if (r.mihpayid() == null || r.mihpayid().isBlank()) {
            row.setReviewReason("NO_PROVIDER_ID"); // no stable credit reference: refuse to guess
            log.warn("PayU success without mihpayid txnid={}", row.getTxnid());
            return;
        }
        // The fee is PayU's charge on top, never a repayment; it is only sanity-checked against what we quoted.
        Long fee = r.additionalCharges() == null || r.additionalCharges().isBlank() ? Long.valueOf(0)
                : paise(r.additionalCharges());
        row.setFeePaise(fee);
        if (fee == null || (fee != 0 && !quotedFees(row.getAdditionalCharges()).contains(fee))) {
            row.setReviewReason("FEE_MISMATCH"); // repayment money did arrive, so it is still credited
            log.warn("PayU fee not as quoted txnid={}", row.getTxnid());
        }
        credit(row, amount);
    }

    private void credit(PayuOrder row, long amount) {
        Long loanId = row.getLoanId();
        String ref = row.getMihpayid();
        Optional<Payment> existing = payments.findFirstByLoanIdAndTxnRef(loanId, ref);
        if (existing.isPresent()) {
            row.setPaymentId(existing.get().getId());
            return;
        }
        try {
            boolean[] over = {false};
            Long id = creditTx().execute(s -> {
                over[0] = amount > repayments.outstandingAsOf(loanId, row.getPaidOn());
                return recordAsSystem(loanId, amount, ref, row.getPaidOn());
            });
            row.setPaymentId(id);
            if (over[0] && row.getReviewReason() == null) {
                row.setReviewReason("OVERPAID");
            }
        } catch (BusinessException e) {
            if (!"LOAN_SETTLED".equals(e.getCode())) {
                throw e;
            }
            // The loan closed while the payment was in flight: money we no longer owe. Parked for refund.
            row.setOverpayment(true);
            row.setReviewReason("LOAN_SETTLED");
            log.warn("PayU payment landed on a settled loan txnid={} loanId={}", row.getTxnid(), loanId);
        }
    }

    /**
     * recordPayment refuses a BORROWER actor without a screenshot and applies the borrower back-dating rule,
     * and this can run on the borrower's own poll. A PayU confirmation is neither, so credit as the system.
     */
    private Long recordAsSystem(Long loanId, long amount, String ref, LocalDate paidOn) {
        CurrentActor previous = ActorContext.get();
        ActorContext.set(CurrentActor.SYSTEM);
        try {
            Payment p = repayments.recordPayment(loanId, amount, PaymentMethod.PAYU, ref, null, paidOn);
            repayments.verifyPayment(p.getId());
            return p.getId();
        } finally {
            if (previous == CurrentActor.SYSTEM) {
                ActorContext.clear();
            } else {
                ActorContext.set(previous);
            }
        }
    }

    /** Opened per call: the credit runs in its own transaction so a caught LOAN_SETTLED cannot poison ours. */
    private TransactionTemplate creditTx() {
        TransactionTemplate t = new TransactionTemplate(tx.getTransactionManager());
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private Loan requireBorrowerOwns(Long loanId) {
        if (!"BORROWER".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role BORROWER");
        }
        return repayments.requireReadableLoan(loanId); // ownership; DSA stays rejected
    }

    private static Set<Long> quotedFees(String additionalCharges) {
        Set<Long> out = new HashSet<>();
        if (additionalCharges != null) {
            for (String part : additionalCharges.split(",")) {
                int i = part.indexOf(':');
                Long v = i < 0 ? null : paise(part.substring(i + 1));
                if (v != null) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    static Long paise(String rupees) {
        if (rupees == null || rupees.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(rupees.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.UNNECESSARY)
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    /** {@code addedon} is IST ("2026-11-09 02:05:41", sandbox-verified); fall back to today if it is unreadable. */
    private static LocalDate paidOn(String addedon) {
        try {
            return LocalDate.parse(addedon.trim().substring(0, 10));
        } catch (RuntimeException e) {
            return LocalDate.now(IST);
        }
    }

    /**
     * PayU requires an email and rejects characters common in real addresses ({@code +}) or anything over 50
     * characters. Those borrowers must still be able to pay, so an unusable address gets a stand-in: refusing
     * here would strand them, since manual payment only opens after a failed attempt.
     */
    static String payuEmail(String email, Long loanId) {
        String e = email == null ? "" : email.trim();
        return SAFE_EMAIL.matcher(e).matches() ? e : "pay" + loanId + "@dhanboost.com";
    }

    static String sanitiseName(String full) {
        String s = full == null ? "" : full.replaceAll("[^A-Za-z ]", " ").replaceAll("\\s+", " ").trim();
        if (s.length() > 60) {
            s = s.substring(0, 60).trim();
        }
        return s.isEmpty() ? "Customer" : s;
    }

    private static String lastTenDigits(String mobile) {
        String d = mobile == null ? "" : mobile.replaceAll("\\D", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    private static String randomHex(int chars) {
        byte[] b = new byte[(chars + 1) / 2];
        RNG.nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return s.substring(0, chars);
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
