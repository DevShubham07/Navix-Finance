package com.navix.disbursement.controller;

import com.navix.disbursement.service.AccountantConfirmationService;
import com.navix.disbursement.service.ApprovalChainService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the disbursement approval chain and manual transfer
 * confirmation. Maker-checker actions are exposed here.
 *
 * TODO: add @PostMapping handlers for review / approve / release / confirm with DTOs.
 */
@RestController
@RequestMapping("/api/disbursement")
public class DisbursementController {

    private final ApprovalChainService approvalChainService;
    private final AccountantConfirmationService accountantConfirmationService;

    public DisbursementController(ApprovalChainService approvalChainService,
                                  AccountantConfirmationService accountantConfirmationService) {
        this.approvalChainService = approvalChainService;
        this.accountantConfirmationService = accountantConfirmationService;
    }

    // TODO: POST /{id}/credit-review     -> approvalChainService.creditReview
    // TODO: POST /{id}/credit-approve    -> approvalChainService.creditHeadApprove
    // TODO: POST /{id}/release           -> approvalChainService.authoriseRelease
    // TODO: POST /{id}/confirm-transfer  -> accountantConfirmationService.confirmSuccess/Failure
}
