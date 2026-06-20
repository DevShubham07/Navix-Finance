package com.navix.disbursement.service;

import com.navix.disbursement.entity.DisbursementRequest;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * After release is authorised the bank transfer is performed manually. The
 * Accountant then confirms the outcome:
 *  - success -> TRANSFER_CONFIRMED and the loan is activated (ACTIVE).
 *  - failure -> TRANSFER_FAILED for rework.
 *
 * Stub for scaffolding.
 */
@Service
public class AccountantConfirmationService {

    /** Accountant confirms the bank transfer succeeded. TODO: set TRANSFER_CONFIRMED + activate loan. */
    public DisbursementRequest confirmSuccess(UUID requestId, UUID accountantId, String bankReference) {
        // TODO: validate TRANSFER_PENDING, record reference, activate loan via navix-loan.
        throw new UnsupportedOperationException("TODO: implement confirmSuccess");
    }

    /** Accountant marks the bank transfer as failed. TODO: set TRANSFER_FAILED. */
    public DisbursementRequest confirmFailure(UUID requestId, UUID accountantId, String reason) {
        // TODO: validate TRANSFER_PENDING, record reason.
        throw new UnsupportedOperationException("TODO: implement confirmFailure");
    }
}
