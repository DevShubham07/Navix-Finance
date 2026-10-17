package com.navix.loan.service;

import com.navix.common.loan.DebitDirectory;
import com.navix.loan.entity.MandateDebit.Trigger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Loan-module side of {@link DebitDirectory}. Deliberately NOT {@code @Transactional}: {@link DebitService#present}
 * puts the provider call between two commits, and wrapping it would hold the row uncommitted across the call.
 */
@Component
@RequiredArgsConstructor
public class DebitDirectoryAdapter implements DebitDirectory {

    private final DebitService debitService;

    @Override
    public Result presentForCase(Long loanId, Long amountPaise, Long actorId) {
        DebitService.DebitView v = debitService.present(loanId, amountPaise, Trigger.MANUAL, actorId);
        return new Result(v.id(), v.status().name(), v.amountPaise(), v.settlementDate(), v.attemptNo());
    }
}
