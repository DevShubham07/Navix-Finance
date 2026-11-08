package com.navix.loan.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.dto.LoanDtos.RepaymentRequest;
import com.navix.loan.payu.PayuService;
import com.navix.loan.service.RepaymentService;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The record endpoint used to carry no role check at all, so any authenticated staff token — a
 * telecaller, a collection executive, a DSA — could create a PENDING_VERIFICATION payment. It is
 * now restricted to the actors with a legitimate path: the borrower (ownership checked in the
 * service) and Admin. The Accountant verifies payments, so it may not also record them — that
 * would put maker and checker on one person.
 */
class RepaymentControllerRoleTest {

    private final PayuService payu = mock(PayuService.class);
    private final RepaymentController controller = new RepaymentController(mock(RepaymentService.class),
            mock(com.navix.loan.service.CustomerService.class), payu);

    private static final RepaymentRequest REQUEST =
            new RepaymentRequest(500_000L, PaymentMethod.UPI, "TXN-1", null, LocalDate.now());

    @org.junit.jupiter.api.BeforeEach
    void manualAllowedByDefault() {
        org.mockito.Mockito.when(payu.manualAllowed(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void rolesWithoutARepaymentDutyAreRejected() {
        for (String role : new String[] {"TELECALLER", "COLLECTION_EXECUTIVE", "COLLECTION_HEAD", "DSA",
                "CREDIT_EXECUTIVE", "CREDIT_HEAD", "DISBURSEMENT_HEAD", "ACCOUNTANT"}) {
            ActorContext.set(new CurrentActor("1", "Staffer", role));
            assertThatThrownBy(() -> controller.record(1L, REQUEST))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("requires role");
        }
    }

    @Test
    void borrowerAndAdminMayRecord() {
        for (String role : new String[] {"BORROWER", "ADMIN"}) {
            ActorContext.set(new CurrentActor("1", "Actor", role));
            assertThatCode(() -> controller.record(1L, REQUEST)).doesNotThrowAnyException();
        }
    }

    @Test
    void aBorrowersProofMustBeTheirOwnRepaymentUpload() {
        ActorContext.set(new CurrentActor("9000001", "Asha", "BORROWER"));
        for (String key : new String[] {"applications/7/aadhaar_card_front/1.jpg", "loan/repayment-proof/../x"}) {
            RepaymentRequest withProof = new RepaymentRequest(500_000L, PaymentMethod.UPI, "TXN-1", key, LocalDate.now());
            assertThatThrownBy(() -> controller.record(1L, withProof))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_PROOF_KEY");
        }
        RepaymentRequest own = new RepaymentRequest(500_000L, PaymentMethod.UPI, "TXN-1",
                "loan/repayment-proof/uuid-shot.jpg", LocalDate.now());
        assertThatCode(() -> controller.record(1L, own)).doesNotThrowAnyException();
    }
    @Test
    void borrowerCannotClaimAPayuPaymentOrUseManualWhenPayuHasNotFailed() {
        ActorContext.set(new CurrentActor("9000001", "Asha", "BORROWER"));
        RepaymentRequest claimsPayu = new RepaymentRequest(500_000L, PaymentMethod.PAYU, "X", "loan/repayment-proof/a.jpg", LocalDate.now());
        assertThatThrownBy(() -> controller.record(1L, claimsPayu))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("code", "INVALID_METHOD");

        org.mockito.Mockito.when(payu.manualAllowed(1L)).thenReturn(false);
        RepaymentRequest manual = new RepaymentRequest(500_000L, PaymentMethod.UPI, "TXN-1", "loan/repayment-proof/a.jpg", LocalDate.now());
        assertThatThrownBy(() -> controller.record(1L, manual))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("code", "MANUAL_PAYMENT_NOT_AVAILABLE");

        // ADMIN is unaffected by either rule.
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        assertThatCode(() -> controller.record(1L, manual)).doesNotThrowAnyException();
    }
}
