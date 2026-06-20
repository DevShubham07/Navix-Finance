package com.navix.collections.service;

import com.navix.collections.entity.RepaymentPlan;
import com.navix.collections.entity.Settlement;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maker-checker workflow for partial settlements and hardship repayment plans:
 * a collections officer PROPOSES and the Collections Head APPROVES. Proposer and
 * approver must be different people.
 *
 * Business logic is stubbed for scaffolding.
 */
@Service
public class SettlementService {

    /** Officer proposes a partial settlement. TODO: implement. */
    public Settlement proposeSettlement(UUID caseId, BigDecimal amount, UUID officerId) {
        throw new UnsupportedOperationException("TODO: implement proposeSettlement");
    }

    /** Collections Head approves a settlement. TODO: enforce approver != proposer. */
    public Settlement approveSettlement(UUID settlementId, UUID headId) {
        throw new UnsupportedOperationException("TODO: implement approveSettlement");
    }

    /** Officer proposes a hardship revised repayment plan. TODO: implement. */
    public RepaymentPlan proposeRepaymentPlan(UUID caseId, LocalDate revisedDueDate, UUID officerId) {
        throw new UnsupportedOperationException("TODO: implement proposeRepaymentPlan");
    }

    /** Collections Head approves a revised repayment plan. TODO: enforce approver != proposer. */
    public RepaymentPlan approveRepaymentPlan(UUID planId, UUID headId) {
        throw new UnsupportedOperationException("TODO: implement approveRepaymentPlan");
    }
}
