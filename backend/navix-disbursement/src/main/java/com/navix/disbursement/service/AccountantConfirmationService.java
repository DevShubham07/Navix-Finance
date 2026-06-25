package com.navix.disbursement.service;

import com.navix.disbursement.entity.DisbursementRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * After release is authorised the bank transfer is performed manually; the Accountant then confirms
 * the outcome. This is a thin façade over {@link ApprovalChainService#confirmTransfer} so the
 * accountant step (its own separation-of-duties and state checks included) reads cleanly from the
 * controller:
 * <ul>
 *   <li>success → {@code TRANSFER_CONFIRMED} (loan activation is driven by navix-loan at go-live);</li>
 *   <li>failure → {@code TRANSFER_FAILED} for rework.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AccountantConfirmationService {

    private final ApprovalChainService approvalChainService;

    /** Accountant confirms the bank transfer succeeded → {@code TRANSFER_CONFIRMED}. */
    @Transactional
    public DisbursementRequest confirmSuccess(UUID requestId) {
        return approvalChainService.confirmTransfer(requestId, true);
    }

    /** Accountant marks the bank transfer as failed → {@code TRANSFER_FAILED}. */
    @Transactional
    public DisbursementRequest confirmFailure(UUID requestId) {
        return approvalChainService.confirmTransfer(requestId, false);
    }

    /** Confirm success or failure based on the {@code success} flag. */
    @Transactional
    public DisbursementRequest confirm(UUID requestId, boolean success) {
        return approvalChainService.confirmTransfer(requestId, success);
    }
}
