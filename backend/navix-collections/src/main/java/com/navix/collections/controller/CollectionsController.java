package com.navix.collections.controller;

import com.navix.collections.service.CollectionsService;
import com.navix.collections.service.SettlementService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for collections: case management, interaction logging, and the
 * maker-checker settlement / repayment-plan workflow.
 *
 * TODO: add @GetMapping/@PostMapping handlers with DTOs for each operation.
 */
@RestController
@RequestMapping("/api/collections")
public class CollectionsController {

    private final CollectionsService collectionsService;
    private final SettlementService settlementService;

    public CollectionsController(CollectionsService collectionsService,
                                 SettlementService settlementService) {
        this.collectionsService = collectionsService;
        this.settlementService = settlementService;
    }

    // TODO: POST /cases                       -> collectionsService.openCase
    // TODO: POST /cases/{id}/assign           -> collectionsService.assignOfficer
    // TODO: POST /cases/{id}/interactions     -> collectionsService.logInteraction
    // TODO: POST /cases/{id}/settlements      -> settlementService.proposeSettlement
    // TODO: POST /settlements/{id}/approve    -> settlementService.approveSettlement
    // TODO: POST /cases/{id}/repayment-plans  -> settlementService.proposeRepaymentPlan
    // TODO: POST /repayment-plans/{id}/approve-> settlementService.approveRepaymentPlan
}
