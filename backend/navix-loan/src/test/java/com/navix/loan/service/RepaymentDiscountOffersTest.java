package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Payment;
import com.navix.loan.entity.RepaymentDiscountOffer;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.repository.RepaymentDiscountOfferRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RepaymentDiscountOffersTest {

    private static final LocalDate DEADLINE = LocalDate.of(2026, 7, 10);

    @Mock
    private RepaymentDiscountOfferRepository offers;
    @Mock
    private LoanRepository loans;
    @Mock
    private PaymentRepository payments;

    private RepaymentDiscountOffers service() {
        return new RepaymentDiscountOffers(offers, loans, payments);
    }

    private static Loan loan(long id, LoanStatus status) {
        Loan l = new Loan();
        l.setId(id);
        l.setStatus(status);
        return l;
    }

    private static Payment paid(LocalDate on) {
        Payment p = new Payment();
        p.setStatus(PaymentStatus.VERIFIED);
        p.setPaidOn(on);
        return p;
    }

    private static RepaymentDiscountOffer offer(long id, long loanId, int pct) {
        RepaymentDiscountOffer o = new RepaymentDiscountOffer();
        o.setId(id);
        o.setCustomerId(7L);
        o.setLoanId(loanId);
        o.setPercent((short) pct);
        o.setRepayBy(DEADLINE);
        return o;
    }

    @Test
    void paidOnTheDeadlineQualifiesButOneDayLateDoesNot() {
        RepaymentDiscountOffer o = offer(1, 50, 5);
        assertThat(RepaymentDiscountOffers.qualifies(o, loan(50, LoanStatus.CLOSED), List.of(paid(DEADLINE)))).isTrue();
        assertThat(RepaymentDiscountOffers.qualifies(o, loan(50, LoanStatus.CLOSED),
                List.of(paid(DEADLINE), paid(DEADLINE.plusDays(1))))).isFalse();
    }

    @Test
    void notClosedOrNoVerifiedPaymentOrUsedDoesNotQualify() {
        RepaymentDiscountOffer o = offer(1, 50, 5);
        assertThat(RepaymentDiscountOffers.qualifies(o, loan(50, LoanStatus.ACTIVE), List.of(paid(DEADLINE)))).isFalse();
        assertThat(RepaymentDiscountOffers.qualifies(o, loan(50, LoanStatus.CLOSED), List.of())).isFalse();
        o.setUsedApplicationId(9L);
        assertThat(RepaymentDiscountOffers.qualifies(o, loan(50, LoanStatus.CLOSED), List.of(paid(DEADLINE)))).isFalse();
    }

    @Test
    void highestPercentWinsThenNewest() {
        RepaymentDiscountOffer a = offer(1, 50, 4);
        RepaymentDiscountOffer b = offer(2, 51, 6);
        RepaymentDiscountOffer c = offer(3, 52, 6);
        when(offers.findByCustomerIdAndUsedApplicationIdIsNull(7L)).thenReturn(List.of(a, b, c));
        for (long id : new long[] {50, 51, 52}) {
            when(loans.findById(id)).thenReturn(Optional.of(loan(id, LoanStatus.CLOSED)));
            when(payments.findByLoanId(id)).thenReturn(List.of(paid(DEADLINE)));
        }
        assertThat(service().bestFor(7L)).get().extracting(RepaymentDiscountOffer::getId).isEqualTo(3L);
    }

    @Test
    void secondConsumptionIsRefused() {
        LoanApplication app = new LoanApplication();
        app.setId(5L);
        app.setFeeDiscountOfferId(2L);
        when(offers.claim(eq(2L), eq(5L), eq(80L), any())).thenReturn(1).thenReturn(0);
        service().consume(app, 80L);
        assertThatThrownBy(() -> service().consume(app, 81L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("already used");
    }
}
