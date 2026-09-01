package com.navix.loan.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.dto.LoanDtos.RepaymentRequest;
import com.navix.loan.service.RepaymentService;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The record endpoint used to carry no role check at all, so any authenticated staff token — a
 * telecaller, a collection executive, a DSA — could create a PENDING_VERIFICATION payment. It is
 * now restricted to the actors with a legitimate path: the borrower (ownership checked in the
 * service), the Accountant (two-step record→verify), and Admin.
 */
class RepaymentControllerRoleTest {

    private final RepaymentController controller = new RepaymentController(mock(RepaymentService.class));

    private static final RepaymentRequest REQUEST =
            new RepaymentRequest(500_000L, PaymentMethod.UPI, "TXN-1", null, LocalDate.now());

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void rolesWithoutARepaymentDutyAreRejected() {
        for (String role : new String[] {"TELECALLER", "COLLECTION_EXECUTIVE", "COLLECTION_HEAD", "DSA",
                "CREDIT_EXECUTIVE", "CREDIT_HEAD", "DISBURSEMENT_HEAD"}) {
            ActorContext.set(new CurrentActor("1", "Staffer", role));
            assertThatThrownBy(() -> controller.record(1L, REQUEST))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("requires role");
        }
    }

    @Test
    void borrowerAccountantAndAdminMayRecord() {
        for (String role : new String[] {"BORROWER", "ACCOUNTANT", "ADMIN"}) {
            ActorContext.set(new CurrentActor("1", "Actor", role));
            assertThatCode(() -> controller.record(1L, REQUEST)).doesNotThrowAnyException();
        }
    }
}
