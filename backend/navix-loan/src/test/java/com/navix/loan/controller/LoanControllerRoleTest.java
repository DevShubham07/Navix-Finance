package com.navix.loan.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.service.RepaymentService;
import com.navix.loan.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The pending-repayments queue lists every borrower's payment with their name and a proof link. It
 * sits under /api/loan, which any token reaches, so the role check is the only thing in the way.
 */
class LoanControllerRoleTest {

    private final RepaymentService repaymentService = mock(RepaymentService.class);
    private final LoanController controller = new LoanController(repaymentService, mock(TransactionService.class));

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    @Test
    void pendingRepayments_isClosedToBorrowersAndOtherStaff() {
        for (String role : new String[] {"BORROWER", "DSA", "TELECALLER", "COLLECTION_EXECUTIVE", "CREDIT_HEAD"}) {
            ActorContext.set(new CurrentActor("1", "Someone", role));
            assertThatThrownBy(controller::pendingRepayments)
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
        }
        verifyNoInteractions(repaymentService);
    }

    @Test
    void pendingRepayments_staysOpenToTheVerifiers() {
        for (String role : new String[] {"ACCOUNTANT", "ADMIN"}) {
            ActorContext.set(new CurrentActor("1", "Verifier", role));
            assertThatCode(controller::pendingRepayments).doesNotThrowAnyException();
        }
    }
}
