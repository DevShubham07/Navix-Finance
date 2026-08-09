package com.navix.loan.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.loan.entity.Payment;
import org.junit.jupiter.api.Test;

class LoanDtosTest {

    @Test
    void paymentViewCanCarryStaffOnlyCustomerContextForTheVerificationQueue() {
        Payment payment = new Payment();
        payment.setId(31L);
        payment.setLoanId(19L);
        payment.setAmount(55_000L);

        var view = LoanDtos.PaymentView.of(payment, 7L, "Asha Rao");

        assertThat(view.customerId()).isEqualTo(7L);
        assertThat(view.customerName()).isEqualTo("Asha Rao");
    }
}
