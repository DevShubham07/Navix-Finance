package com.navix.loan.payu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Everything PayU would say is mocked; the order table is an in-memory map so state really carries over. */
class PayuServiceTest {

    private static final long LOAN = 7L;
    private static final long CUSTOMER = 5L;
    private static final String KEY = "K";
    private static final String SALT = "S";

    private PayuClient client;
    private PayuOrderRepository orders;
    private PaymentRepository payments;
    private RepaymentService repayments;
    private CustomerReviewService reviews;
    private FeatureFlagService flags;
    private PayuService service;
    private final Map<String, PayuOrder> table = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(100);
    private Loan loan;

    @BeforeEach
    void setUp() {
        ActorContext.set(new CurrentActor(String.valueOf(CUSTOMER), "Asha", "BORROWER"));
        client = mock(PayuClient.class);
        orders = mock(PayuOrderRepository.class);
        payments = mock(PaymentRepository.class);
        repayments = mock(RepaymentService.class);
        reviews = mock(CustomerReviewService.class);
        flags = mock(FeatureFlagService.class);
        when(flags.isEnabled("payu-repayment", false)).thenReturn(true);

        PayuProperties props = new PayuProperties(KEY, SALT, "https://test.payu.in/_payment",
                "https://test.payu.in/merchant/postservice.php?form=2", "https://app.test/api/payu/return",
                "CC:236", true);
        service = new PayuService(props, client, orders, payments, repayments, reviews, flags,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));

        when(orders.save(any())).thenAnswer(i -> {
            PayuOrder o = i.getArgument(0);
            if (o.getId() == null) {
                o.setId(ids.incrementAndGet());
            }
            if (o.getCreatedAt() == null) {
                o.setCreatedAt(Instant.now());
            }
            table.put(o.getTxnid(), o);
            return o;
        });
        when(orders.findByTxnid(anyString())).thenAnswer(i -> Optional.ofNullable(table.get(i.<String>getArgument(0))));
        when(orders.lockByTxnid(anyString())).thenAnswer(i -> Optional.ofNullable(table.get(i.<String>getArgument(0))));
        when(orders.findFirstByLoanIdOrderByCreatedAtDescIdDesc(anyLong())).thenAnswer(i -> newestFirst().stream().findFirst());
        when(orders.findByLoanIdAndStatusInOrderByCreatedAtDescIdDesc(anyLong(), any())).thenAnswer(i -> {
            java.util.Collection<Status> wanted = i.getArgument(1);
            return newestFirst().stream().filter(o -> wanted.contains(o.getStatus())).toList();
        });

        loan = new Loan();
        loan.setId(LOAN);
        loan.setCustomerId(CUSTOMER);
        loan.setStatus(LoanStatus.ACTIVE);
        when(repayments.requireReadableLoan(LOAN)).thenReturn(loan);
        when(repayments.outstandingAsOf(anyLong(), any())).thenReturn(500_000L);
        when(payments.findByLoanId(LOAN)).thenReturn(List.of());
        when(payments.findFirstByLoanIdAndTxnRef(anyLong(), anyString())).thenReturn(Optional.empty());
        when(repayments.recordPayment(anyLong(), anyLong(), any(), anyString(), any(), any())).thenAnswer(i -> {
            Payment p = new Payment();
            p.setId(ids.incrementAndGet());
            return p;
        });

        CustomerProfile profile = new CustomerProfile();
        profile.setFullName("Asha K. Rao");
        profile.setEmail("asha@example.com");
        profile.setMobile("+91 98765 43210");
        when(reviews.latestProfile(CUSTOMER)).thenReturn(Optional.of(profile));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private List<PayuOrder> newestFirst() {
        List<PayuOrder> l = new ArrayList<>(table.values());
        l.sort(Comparator.comparing(PayuOrder::getCreatedAt).thenComparing(PayuOrder::getId).reversed());
        return l;
    }

    private PayuOrder order(String txnid, Status status, long amountPaise) {
        PayuOrder o = new PayuOrder();
        o.setId(ids.incrementAndGet());
        o.setTxnid(txnid);
        o.setLoanId(LOAN);
        o.setCustomerId(CUSTOMER);
        o.setAmountPaise(amountPaise);
        o.setStatus(status);
        o.setCreatedAt(Instant.now());
        table.put(txnid, o);
        return o;
    }

    private static VerifyResult captured(String amount, String fee) {
        return new VerifyResult(true, "success", "captured", "MIH1", amount, fee, "UPI", "UPI", "BANKREF",
                LocalDate.now(ZoneId.of("Asia/Kolkata")) + " 10:00:00", "E000");
    }

    private static VerifyResult pending(String unmapped) {
        return new VerifyResult(true, "pending", unmapped, "MIH1", "1000.00", null, "-", null, null,
                "2026-11-09 01:00:00", null);
    }

    private static VerifyResult failed() {
        return new VerifyResult(true, "failure", "failed", "MIH1", "1000.00", null, "UPI", "UPI", null,
                "2026-11-09 01:00:00", "E308");
    }

    private void verifyReturns(VerifyResult r) {
        when(client.verify(anyString())).thenReturn(Optional.of(r));
    }

    private static void assertCode(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(code);
    }

    private Map<String, String> signedCallback(String txnid) {
        Map<String, String> f = new HashMap<>();
        f.put("txnid", txnid);
        f.put("status", "success");
        f.put("amount", "1000.00");
        f.put("key", KEY);
        f.put("udf1", String.valueOf(LOAN));
        f.put("email", "asha@example.com");
        f.put("firstname", "Asha");
        f.put("productinfo", "DhanBoost loan repayment");
        f.put("hash", PayuHash.reverse(f, SALT));
        return f;
    }

    // ---- crediting ---------------------------------------------------------------------------------

    @Test
    void success_creditsOnce_andASecondCallbackDoesNot() {
        order("DB1", Status.INITIATED, 100_000);
        verifyReturns(captured("1000.00", "0.00"));

        PayuOrder after = service.reconcile("DB1");
        assertThat(after.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(after.getPaymentId()).isNotNull();
        assertThat(after.getMihpayid()).isEqualTo("MIH1");
        verify(repayments, times(1)).recordPayment(eq(LOAN), eq(100_000L), eq(PaymentMethod.PAYU), eq("MIH1"),
                isNull(), any());
        verify(repayments, times(1)).verifyPayment(after.getPaymentId());

        assertThat(service.onCallback(signedCallback("DB1"))).isEqualTo("DB1");
        service.reconcile("DB1");
        verify(repayments, times(1)).recordPayment(anyLong(), anyLong(), any(), anyString(), any(), any());
        verify(client, times(1)).verify(anyString()); // a finished order is not even re-read
    }

    @Test
    void creditRunsAsTheSystem_notAsTheBorrowerWhoseProofRuleWouldRefuseIt() {
        order("DB1", Status.INITIATED, 100_000);
        verifyReturns(captured("1000.00", "0.00"));
        AtomicReference<String> roleDuringCredit = new AtomicReference<>();
        when(repayments.recordPayment(anyLong(), anyLong(), any(), anyString(), any(), any())).thenAnswer(i -> {
            roleDuringCredit.set(ActorContext.get().role());
            Payment p = new Payment();
            p.setId(1L);
            return p;
        });

        service.reconcile("DB1");

        assertThat(roleDuringCredit.get()).isEqualTo("SYSTEM");
        assertThat(ActorContext.get().role()).isEqualTo("BORROWER");
    }

    @Test
    void existingPaymentWithTheSameReference_isLinked_notCreditedAgain() {
        order("DB1", Status.INITIATED, 100_000);
        verifyReturns(captured("1000.00", "0.00"));
        Payment already = new Payment();
        already.setId(55L);
        when(payments.findFirstByLoanIdAndTxnRef(LOAN, "MIH1")).thenReturn(Optional.of(already));

        PayuOrder after = service.reconcile("DB1");

        assertThat(after.getPaymentId()).isEqualTo(55L);
        verify(repayments, never()).recordPayment(anyLong(), anyLong(), any(), anyString(), any(), any());
    }

    @Test
    void amountMismatch_isSuccessButNeverCredited() {
        order("DB1", Status.INITIATED, 100_000);
        verifyReturns(captured("999.00", "0.00"));

        PayuOrder after = service.reconcile("DB1");

        assertThat(after.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(after.getReviewReason()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(after.getPaymentId()).isNull();
        verify(repayments, never()).recordPayment(anyLong(), anyLong(), any(), anyString(), any(), any());
    }

    @Test
    void creditUsesTransactionAmount_neverTheFeeInclusiveTotal() {
        PayuOrder o = order("DB1", Status.INITIATED, 10_000);
        o.setAdditionalCharges("CC:2.36,DC:0,NB:0");
        // transaction_amount 100.00 + fee 2.36 (amt / net_amount_debit would say 102.36)
        verifyReturns(captured("100.00", "2.36"));

        PayuOrder after = service.reconcile("DB1");

        verify(repayments).recordPayment(eq(LOAN), eq(10_000L), eq(PaymentMethod.PAYU), eq("MIH1"), isNull(), any());
        assertThat(after.getFeePaise()).isEqualTo(236L);
        assertThat(after.getReviewReason()).isNull();
    }

    @Test
    void feeNotAsQuoted_isFlaggedButStillCredited() {
        PayuOrder o = order("DB1", Status.INITIATED, 10_000);
        o.setAdditionalCharges("CC:2.36,DC:0,NB:0");
        verifyReturns(captured("100.00", "9.99"));

        PayuOrder after = service.reconcile("DB1");

        assertThat(after.getReviewReason()).isEqualTo("FEE_MISMATCH");
        assertThat(after.getPaymentId()).isNotNull();
        verify(repayments).recordPayment(eq(LOAN), eq(10_000L), any(), anyString(), any(), any());
    }

    @Test
    void settledLoan_marksOverpayment_andDoesNotThrow() {
        order("DB1", Status.INITIATED, 100_000);
        verifyReturns(captured("1000.00", "0.00"));
        when(repayments.recordPayment(anyLong(), anyLong(), any(), anyString(), any(), any()))
                .thenThrow(new BusinessException("LOAN_SETTLED", "Loan is already settled"));

        PayuOrder after = service.reconcile("DB1");

        assertThat(after.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(after.isOverpayment()).isTrue();
        assertThat(after.getReviewReason()).isEqualTo("LOAN_SETTLED");
        assertThat(after.getPaymentId()).isNull();
    }

    @Test
    void anyOtherCreditFailure_propagates_soTheOrderStaysOpenForTheNextRun() {
        order("DB1", Status.INITIATED, 100_000);
        verifyReturns(captured("1000.00", "0.00"));
        when(repayments.recordPayment(anyLong(), anyLong(), any(), anyString(), any(), any()))
                .thenThrow(new BusinessException("INVALID_PAID_ON", "x"));

        assertCode(() -> service.reconcile("DB1"), "INVALID_PAID_ON");
    }

    // ---- statuses ----------------------------------------------------------------------------------

    @Test
    void pending_staysOpen_andInitiatedIsNotPending() {
        order("DB1", Status.INITIATED, 100_000);
        verifyReturns(pending("in progress"));
        PayuOrder a = service.reconcile("DB1");
        assertThat(a.getStatus()).isEqualTo(Status.PENDING);
        assertThat(a.getNextCheckAt()).isNotNull();
        assertThat(a.getPaymentId()).isNull();

        order("DB2", Status.INITIATED, 100_000);
        verifyReturns(pending("initiated"));
        assertThat(service.reconcile("DB2").getStatus()).isEqualTo(Status.INITIATED);
        verify(repayments, never()).recordPayment(anyLong(), anyLong(), any(), anyString(), any(), any());
    }

    @Test
    void authIsNotCaptured_noCredit() {
        order("DB1", Status.INITIATED, 100_000);
        verifyReturns(new VerifyResult(true, "success", "auth", "MIH1", "1000.00", null, "CC", "CC", null,
                "2026-11-09 01:00:00", null));
        assertThat(service.reconcile("DB1").getStatus()).isEqualTo(Status.PENDING);
        verify(repayments, never()).recordPayment(anyLong(), anyLong(), any(), anyString(), any(), any());
    }

    @Test
    void notFound_staysInitiated() {
        order("DB1", Status.INITIATED, 100_000);
        verifyReturns(new VerifyResult(false, "Not Found", null, "Not Found", null, null, null, null, null, null, null));
        assertThat(service.reconcile("DB1").getStatus()).isEqualTo(Status.INITIATED);
    }

    @Test
    void unknownAnswer_changesNothing_butSchedulesTheNextLook() {
        PayuOrder o = order("DB1", Status.PENDING, 100_000);
        when(client.verify("DB1")).thenReturn(Optional.empty());
        PayuOrder after = service.reconcile("DB1");
        assertThat(after.getStatus()).isEqualTo(Status.PENDING);
        assertThat(after.getCheckCount()).isEqualTo(1);
        assertThat(o.getNextCheckAt()).isNotNull();
    }

    @Test
    void failure_thenALateSuccess_stillCredits() {
        order("DB1", Status.INITIATED, 100_000);
        verifyReturns(failed());
        PayuOrder f = service.reconcile("DB1");
        assertThat(f.getStatus()).isEqualTo(Status.FAILED);
        assertThat(f.getErrorCode()).isEqualTo("E308");
        assertThat(f.getNextCheckAt()).isNull();

        verifyReturns(captured("1000.00", "0.00"));
        PayuOrder s = service.reconcile("DB1");
        assertThat(s.getStatus()).isEqualTo(Status.SUCCESS);
        verify(repayments, times(1)).recordPayment(eq(LOAN), eq(100_000L), any(), anyString(), any(), any());
    }

    @Test
    void successIsNeverDowngraded() {
        // A SUCCESS row that is not parked or credited yet (a half-finished earlier run).
        order("DB1", Status.SUCCESS, 100_000);
        verifyReturns(failed());
        assertThat(service.reconcile("DB1").getStatus()).isEqualTo(Status.SUCCESS);
        verifyReturns(pending("in progress"));
        assertThat(service.reconcile("DB1").getStatus()).isEqualTo(Status.SUCCESS);
    }

    @Test
    void openOrderOlderThan24Hours_expires() {
        PayuOrder o = order("DB1", Status.INITIATED, 100_000);
        o.setCreatedAt(Instant.now().minusSeconds(25 * 3600));
        verifyReturns(pending("initiated"));
        assertThat(service.reconcile("DB1").getStatus()).isEqualTo(Status.EXPIRED);
    }

    // ---- callbacks ---------------------------------------------------------------------------------

    @Test
    void forgedOrUnknownCallback_isIgnored_andNeverReachesPayu() {
        order("DB1", Status.INITIATED, 100_000);
        Map<String, String> forged = signedCallback("DB1");
        forged.put("hash", "0".repeat(128));
        assertThat(service.onCallback(forged)).isNull();
        assertThat(service.onCallback(signedCallback("NOPE"))).isNull();
        assertThat(service.onCallback(Map.of())).isNull();
        verify(client, never()).verify(anyString());
        verify(repayments, never()).recordPayment(anyLong(), anyLong(), any(), anyString(), any(), any());
    }

    @Test
    void validCallback_neverTrustsItsOwnStatus() {
        order("DB1", Status.INITIATED, 100_000);
        verifyReturns(failed()); // the body says success, PayU says failure
        assertThat(service.onCallback(signedCallback("DB1"))).isEqualTo("DB1");
        assertThat(table.get("DB1").getStatus()).isEqualTo(Status.FAILED);
        verify(repayments, never()).recordPayment(anyLong(), anyLong(), any(), anyString(), any(), any());
    }

    @Test
    void sweep_oneFailureDoesNotStopTheRest() {
        order("DB1", Status.INITIATED, 100_000).setNextCheckAt(Instant.now().minusSeconds(1));
        order("DB2", Status.INITIATED, 100_000).setNextCheckAt(Instant.now().minusSeconds(1));
        when(orders.findDue(any(), any(), any())).thenReturn(List.of(table.get("DB1"), table.get("DB2")));
        when(client.verify("DB1")).thenThrow(new IllegalStateException("boom"));
        when(client.verify("DB2")).thenReturn(Optional.of(captured("1000.00", "0.00")));

        assertThat(service.reconcileDue()).isEqualTo(1);
        assertThat(table.get("DB2").getStatus()).isEqualTo(Status.SUCCESS);
    }

    // ---- creating an order -------------------------------------------------------------------------

    @Test
    void createOrder_buildsTheSignedForm_fromServerValues() {
        PayuService.Checkout c = service.createOrder(LOAN, 100_000);

        Map<String, String> f = c.fields();
        assertThat(c.action()).isEqualTo("https://test.payu.in/_payment");
        assertThat(f.get("txnid")).startsWith("DB").hasSize(22);
        assertThat(f.get("amount")).isEqualTo("1000.00");
        assertThat(f.get("udf1")).isEqualTo("7");
        assertThat(f.get("firstname")).isEqualTo("Asha K Rao");
        assertThat(f.get("phone")).isEqualTo("9876543210");
        assertThat(f.get("surl")).isEqualTo("https://app.test/api/payu/return");
        assertThat(f.get("additional_charges")).isEqualTo("CC:23.60,DC:0,NB:0");
        assertThat(f).doesNotContainKey("salt");
        assertThat(f.get("hash")).isEqualTo(PayuHash.request(KEY, f.get("txnid"), "1000.00",
                "DhanBoost loan repayment", "Asha K Rao", "asha@example.com", "7", "", "", "", "", SALT,
                f.get("additional_charges")));
        PayuOrder saved = table.get(f.get("txnid"));
        assertThat(saved.getStatus()).isEqualTo(Status.INITIATED);
        assertThat(saved.getAmountPaise()).isEqualTo(100_000);
        assertThat(saved.getNextCheckAt()).isAfter(Instant.now());
    }

    @Test
    void createOrder_refusals() {
        assertCode(() -> service.createOrder(LOAN, 99), "INVALID_AMOUNT");

        when(repayments.outstandingAsOf(anyLong(), any())).thenReturn(50_000L);
        assertCode(() -> service.createOrder(LOAN, 60_000), "AMOUNT_EXCEEDS_OUTSTANDING");

        Payment waiting = new Payment();
        waiting.setStatus(PaymentStatus.PENDING_VERIFICATION);
        when(payments.findByLoanId(LOAN)).thenReturn(List.of(waiting));
        assertCode(() -> service.createOrder(LOAN, 10_000), "MANUAL_PAYMENT_PENDING");
        when(payments.findByLoanId(LOAN)).thenReturn(List.of());

        loan.setStatus(LoanStatus.CLOSED);
        assertCode(() -> service.createOrder(LOAN, 10_000), "LOAN_SETTLED");
        loan.setStatus(LoanStatus.ACTIVE);

        when(flags.isEnabled("payu-repayment", false)).thenReturn(false);
        assertCode(() -> service.createOrder(LOAN, 10_000), "PAYU_DISABLED");
        when(flags.isEnabled("payu-repayment", false)).thenReturn(true);

        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        assertCode(() -> service.createOrder(LOAN, 10_000), "FORBIDDEN_ROLE");
        assertThat(table).isEmpty();
    }

    @Test
    void createOrder_recentPendingOrderBlocks_oldOneDoesNot() {
        PayuOrder open = order("DB1", Status.PENDING, 100_000);
        verifyReturns(pending("in progress"));
        assertCode(() -> service.createOrder(LOAN, 10_000), "PAYU_IN_PROGRESS");

        open.setCreatedAt(Instant.now().minusSeconds(20 * 60));
        assertThat(service.createOrder(LOAN, 10_000).order().status()).isEqualTo("INITIATED");
    }

    @Test
    void createOrder_merelyInitiatedOrderDoesNotBlock() {
        order("DB1", Status.INITIATED, 100_000);
        verifyReturns(pending("initiated"));
        assertThat(service.createOrder(LOAN, 10_000).fields()).containsKey("hash");
    }

    @Test
    void createOrder_refusesWhenTheOpenOrderTurnedOutPaid() {
        order("DB1", Status.PENDING, 100_000);
        verifyReturns(captured("1000.00", "0.00"));
        assertCode(() -> service.createOrder(LOAN, 10_000), "PAYU_PAYMENT_RECEIVED");
        verify(repayments, times(1)).verifyPayment(anyLong()); // the credit ran
    }

    // ---- manual fallback ---------------------------------------------------------------------------

    @Test
    void manualAllowed_truthTable() {
        when(flags.isEnabled("payu-repayment", false)).thenReturn(false);
        assertThat(service.manualAllowed(LOAN)).isTrue(); // flag off: today's behaviour

        when(flags.isEnabled("payu-repayment", false)).thenReturn(true);
        assertThat(service.manualAllowed(LOAN)).isFalse(); // no order yet

        PayuOrder o = order("DB1", Status.FAILED, 100_000);
        assertThat(service.manualAllowed(LOAN)).isTrue(); // latest failed
        o.setStatus(Status.EXPIRED);
        assertThat(service.manualAllowed(LOAN)).isTrue();
        o.setStatus(Status.SUCCESS);
        assertThat(service.manualAllowed(LOAN)).isFalse();
        o.setStatus(Status.PENDING);
        assertThat(service.manualAllowed(LOAN)).isFalse();

        o.setStatus(Status.FAILED);
        order("DB2", Status.INITIATED, 100_000).setCreatedAt(Instant.now().plusSeconds(1)); // newer, open
        assertThat(service.manualAllowed(LOAN)).isFalse();
    }

    @Test
    void manualAllowed_opensOnceAnInitiatedCheckoutIsAbandoned_butNeverOnABankPayment() {
        PayuOrder o = order("DB1", Status.INITIATED, 100_000);
        o.setCreatedAt(Instant.now().minusSeconds(29 * 60));
        assertThat(service.manualAllowed(LOAN)).isFalse(); // may still be on PayU's page

        o.setCreatedAt(Instant.now().minusSeconds(31 * 60));
        assertThat(service.manualAllowed(LOAN)).isTrue(); // closed tab or PayU outage

        o.setStatus(Status.PENDING); // the bank has it: a second, manual payment could double-pay
        assertThat(service.manualAllowed(LOAN)).isFalse();
    }

    @Test
    void manualAllowed_asksPayuBeforeOpeningOnAnAgedCheckout_soALivePaymentIsNotPaidTwice() {
        PayuOrder o = order("DB1", Status.INITIATED, 100_000);
        o.setCreatedAt(Instant.now().minusSeconds(31 * 60));
        verifyReturns(pending("in progress")); // our row says INITIATED; the borrower has since reached the bank

        assertThat(service.manualAllowed(LOAN)).isFalse();
        assertThat(o.getStatus()).isEqualTo(Status.PENDING);
    }

    @Test
    void manualAllowed_anEarlierAbandonedCheckoutDoesNotHideManualAfterAFailure() {
        order("DB1", Status.INITIATED, 100_000).setCreatedAt(Instant.now().minusSeconds(120));
        order("DB2", Status.FAILED, 100_000);
        assertThat(service.manualAllowed(LOAN)).isTrue();
    }

    @Test
    void manualAllowed_anEarlierCheckoutThatReachedTheBankStillBlocks() {
        PayuOrder older = order("DB1", Status.INITIATED, 100_000);
        older.setCreatedAt(Instant.now().minusSeconds(120));
        order("DB2", Status.FAILED, 100_000);
        verifyReturns(pending("in progress"));

        assertThat(service.manualAllowed(LOAN)).isFalse();
        assertThat(older.getStatus()).isEqualTo(Status.PENDING);
    }

    @Test
    void createOrder_unusableEmailGetsAStandIn_soTheBorrowerCanStillPay() {
        CustomerProfile plus = new CustomerProfile();
        plus.setFullName("Asha Rao");
        plus.setEmail("asha+loans@example.com"); // '+' is outside PayU's allowed characters
        plus.setMobile("9876543210");
        when(reviews.latestProfile(CUSTOMER)).thenReturn(Optional.of(plus));
        assertThat(service.createOrder(LOAN, 10_000).fields().get("email")).isEqualTo("pay" + LOAN + "@dhanboost.com");

        when(reviews.latestProfile(CUSTOMER)).thenReturn(Optional.empty());
        assertThat(service.createOrder(LOAN, 10_000).fields().get("email")).isEqualTo("pay" + LOAN + "@dhanboost.com");
    }

    @Test
    void enabled_needsTheFlagAndCredentials() {
        PayuService noCreds = new PayuService(new PayuProperties("", "", "u", "c", "r", "", true), client, orders,
                payments, repayments, reviews, flags, new TransactionTemplate(mock(PlatformTransactionManager.class)));
        assertThat(noCreds.enabled()).isFalse();
        assertThat(service.enabled()).isTrue();
    }

    /** The flag flips with no restart, so this must hold on every call, not just at boot. */
    @Test
    void enabled_refusesTheSandboxUnlessExplicitlyAllowed() {
        PayuService prod = new PayuService(new PayuProperties(KEY, SALT, "https://test.payu.in/_payment",
                "https://info.payu.in/merchant/postservice.php?form=2", "r", "", false), client, orders,
                payments, repayments, reviews, flags, new TransactionTemplate(mock(PlatformTransactionManager.class)));
        assertThat(prod.enabled()).isFalse();
        assertThat(prod.manualAllowed(LOAN)).isTrue(); // falls back to manual, never a dead end

        PayuService live = new PayuService(new PayuProperties(KEY, SALT, "https://secure.payu.in/_payment",
                "https://info.payu.in/merchant/postservice.php?form=2", "r", "", false), client, orders,
                payments, repayments, reviews, flags, new TransactionTemplate(mock(PlatformTransactionManager.class)));
        assertThat(live.enabled()).isTrue();
    }

    @Test
    void state_hidesInternals_andQuotesTheCardFee() {
        order("DB1", Status.FAILED, 100_000).setReviewReason("AMOUNT_MISMATCH");
        PayuService.State s = service.state(LOAN, 1_000_000L);
        assertThat(s.enabled()).isTrue();
        assertThat(s.manualAllowed()).isTrue();
        assertThat(s.creditCardFeePaise()).isEqualTo(23_600L);
        assertThat(s.openOrder()).isNull();
        assertThat(s.lastOrder().status()).isEqualTo("FAILED");
        assertThat(service.state(LOAN, null).creditCardFeePaise()).isNull();
    }

    @Test
    void order_mustBelongToThisLoan() {
        PayuOrder o = order("DB1", Status.FAILED, 100_000);
        o.setLoanId(99L);
        assertCode(() -> service.order(LOAN, "DB1"), "ORDER_NOT_FOUND");
    }
}
