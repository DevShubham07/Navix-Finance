package com.navix.loan.payu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.payu.PayuReviewService.ReviewView;
import com.navix.loan.service.TransactionService;
import com.navix.loan.service.TransactionService.BorrowerRef;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayuReviewServiceTest {

    private PayuOrderRepository orders;
    private TransactionService transactions;
    private PayuReviewService service;

    @BeforeEach
    void setUp() {
        orders = mock(PayuOrderRepository.class);
        transactions = mock(TransactionService.class);
        service = new PayuReviewService(orders, transactions);
        as("ACCOUNTANT");
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private static void as(String role) {
        ActorContext.set(new CurrentActor("42", "Staff", role));
    }

    private static PayuOrder order(long id, String reason, Long paymentId) {
        PayuOrder o = new PayuOrder();
        o.setId(id);
        o.setTxnid("DB" + id);
        o.setLoanId(7L);
        o.setCustomerId(5L);
        o.setAmountPaise(123_456);
        o.setFeePaise(2_000L);
        o.setStatus(PayuOrder.Status.SUCCESS);
        o.setMihpayid("403993715");
        o.setBankRefNum("REF1");
        o.setMode("CC");
        o.setPaidOn(LocalDate.of(2026, 11, 1));
        o.setPaymentId(paymentId);
        o.setOverpayment(true);
        o.setReviewReason(reason);
        o.setCreatedAt(Instant.parse("2026-11-01T10:00:00Z"));
        return o;
    }

    @Test
    void listsUnresolvedWithNameFromOneBatchLookup() {
        when(orders.findUnresolvedReview(any())).thenReturn(List.of(
                order(2, "OVERPAID", 9L), order(1, "AMOUNT_MISMATCH", null)));
        when(transactions.borrowersByLoanId(List.of(7L))).thenReturn(Map.of(7L, new BorrowerRef(5L, "Asha Rao")));

        List<ReviewView> out = service.listUnresolved();

        assertThat(out).hasSize(2);
        ReviewView first = out.get(0);
        assertThat(first.orderId()).isEqualTo(2L);
        assertThat(first.customerName()).isEqualTo("Asha Rao");
        assertThat(first.amountPaise()).isEqualTo(123_456);
        assertThat(first.feePaise()).isEqualTo(2_000L);
        assertThat(first.reviewReason()).isEqualTo("OVERPAID");
        assertThat(first.credited()).isTrue();
        assertThat(out.get(1).credited()).isFalse();
        verify(transactions, times(1)).borrowersByLoanId(any());
    }

    @Test
    void onlyAccountantAndAdminMayListOrResolve() {
        for (String role : List.of("DSA", "BORROWER", "CREDIT_HEAD", "COLLECTION_HEAD", "TELECALLER")) {
            as(role);
            assertThatThrownBy(() -> service.listUnresolved())
                    .isInstanceOf(BusinessException.class).hasMessageContaining("ACCOUNTANT");
            assertThatThrownBy(() -> service.resolve(1L, "done"))
                    .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("FORBIDDEN_ROLE");
        }
        as("ADMIN");
        when(orders.findUnresolvedReview(any())).thenReturn(List.of());
        assertThat(service.listUnresolved()).isEmpty();
        verify(orders, never()).lockById(any());
    }

    @Test
    void resolveRequiresANote() {
        for (String note : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.resolve(1L, note))
                    .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("REVIEW_NOTE_REQUIRED");
        }
        verify(orders, never()).save(any());
    }

    @Test
    void resolveRecordsWhoWhenAndLeavesMoneyFieldsAlone() {
        PayuOrder o = order(1, "AMOUNT_MISMATCH", null);
        when(orders.lockById(1L)).thenReturn(Optional.of(o));

        service.resolve(1L, "  refunded in PayU dashboard  ");

        assertThat(o.getReviewResolvedBy()).isEqualTo(42L);
        assertThat(o.getReviewResolvedAt()).isNotNull();
        assertThat(o.getReviewNote()).isEqualTo("refunded in PayU dashboard");
        assertThat(o.getReviewReason()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(o.getStatus()).isEqualTo(PayuOrder.Status.SUCCESS);
        assertThat(o.getPaymentId()).isNull();
        assertThat(o.getAmountPaise()).isEqualTo(123_456);
        assertThat(o.getFeePaise()).isEqualTo(2_000L);
        assertThat(o.isOverpayment()).isTrue();
        verify(orders).save(o);
    }

    @Test
    void resolvingTwiceIsANoOpThatKeepsTheFirstRecord() {
        PayuOrder o = order(1, "LOAN_SETTLED", null);
        when(orders.lockById(1L)).thenReturn(Optional.of(o));
        service.resolve(1L, "first");
        Instant at = o.getReviewResolvedAt();
        as("ADMIN");
        ActorContext.set(new CurrentActor("99", "Other", "ADMIN"));

        service.resolve(1L, "second");

        assertThat(o.getReviewNote()).isEqualTo("first");
        assertThat(o.getReviewResolvedBy()).isEqualTo(42L);
        assertThat(o.getReviewResolvedAt()).isEqualTo(at);
        verify(orders, times(1)).save(o);
    }

    @Test
    void rowWithoutReviewReasonIsNotInReviewAndUnknownIdIsNotFound() {
        when(orders.lockById(1L)).thenReturn(Optional.of(order(1, null, 9L)));
        assertThatThrownBy(() -> service.resolve(1L, "x"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("ORDER_NOT_IN_REVIEW");
        when(orders.lockById(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve(2L, "x"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("ORDER_NOT_FOUND");
    }
}
