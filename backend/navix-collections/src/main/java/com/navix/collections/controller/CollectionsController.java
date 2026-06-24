package com.navix.collections.controller;

import com.navix.collections.dto.CollectionsDtos.AssignOfficerRequest;
import com.navix.collections.dto.CollectionsDtos.CaseView;
import com.navix.collections.dto.CollectionsDtos.DpdView;
import com.navix.collections.dto.CollectionsDtos.InteractionView;
import com.navix.collections.dto.CollectionsDtos.LogInteractionRequest;
import com.navix.collections.dto.CollectionsDtos.OpenCaseRequest;
import com.navix.collections.dto.CollectionsDtos.ProposeSettlementRequest;
import com.navix.collections.dto.CollectionsDtos.SettlementView;
import com.navix.collections.service.CollectionsService;
import com.navix.collections.service.DpdCalculator;
import com.navix.collections.service.SettlementService;
import com.navix.common.web.ApiResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for collections: case management, interaction logging, the
 * maker-checker settlement workflow, and a live DPD-bucket helper. All responses
 * use {@link ApiResponse}.
 */
@RestController
@RequestMapping("/api/collections")
@RequiredArgsConstructor
public class CollectionsController {

    private final CollectionsService collectionsService;
    private final SettlementService settlementService;
    private final DpdCalculator dpdCalculator;

    /** Open (or fetch) the collection case for an overdue loan. */
    @PostMapping("/cases")
    public ApiResponse<CaseView> openCase(@Valid @RequestBody OpenCaseRequest request) {
        return ApiResponse.ok(CaseView.of(collectionsService.openCase(request.loanId())));
    }

    @GetMapping("/cases")
    public ApiResponse<List<CaseView>> listCases() {
        return ApiResponse.ok(collectionsService.listCases().stream().map(CaseView::of).toList());
    }

    @GetMapping("/cases/{caseId}")
    public ApiResponse<CaseView> getCase(@PathVariable UUID caseId) {
        return ApiResponse.ok(CaseView.of(collectionsService.getCase(caseId)));
    }

    /** Assign a collections officer to a case. */
    @PostMapping("/cases/{caseId}/assign")
    public ApiResponse<CaseView> assignOfficer(@PathVariable UUID caseId,
                                               @Valid @RequestBody AssignOfficerRequest request) {
        return ApiResponse.ok(CaseView.of(
                collectionsService.assignOfficer(caseId, request.officerId())));
    }

    /** Log a borrower interaction (PAID outcome requires a proof reference). */
    @PostMapping("/cases/{caseId}/interactions")
    public ApiResponse<InteractionView> logInteraction(@PathVariable UUID caseId,
                                                       @Valid @RequestBody LogInteractionRequest request) {
        return ApiResponse.ok(InteractionView.of(collectionsService.logInteraction(
                caseId, request.type(), request.outcome(),
                request.promiseToPayDate(), request.proofRef())));
    }

    @GetMapping("/cases/{caseId}/interactions")
    public ApiResponse<List<InteractionView>> listInteractions(@PathVariable UUID caseId) {
        return ApiResponse.ok(
                collectionsService.listInteractions(caseId).stream().map(InteractionView::of).toList());
    }

    /** Officer proposes a partial settlement (amount in paise). */
    @PostMapping("/cases/{caseId}/settlements")
    public ApiResponse<SettlementView> proposeSettlement(@PathVariable UUID caseId,
                                                         @Valid @RequestBody ProposeSettlementRequest request) {
        return ApiResponse.ok(SettlementView.of(
                settlementService.propose(caseId, request.settlementAmountPaise())));
    }

    /** All settlements (pending + approved) for the collections settlements worklist. */
    @GetMapping("/settlements")
    public ApiResponse<List<SettlementView>> listSettlements() {
        return ApiResponse.ok(settlementService.listAll().stream().map(SettlementView::of).toList());
    }

    /** Collections Head approves a settlement (separation of duties enforced). */
    @PostMapping("/settlements/{settlementId}/approve")
    public ApiResponse<SettlementView> approveSettlement(@PathVariable UUID settlementId) {
        return ApiResponse.ok(SettlementView.of(settlementService.approve(settlementId)));
    }

    /** Live DPD-bucket helper: days-past-due + bucket for a due date as of a date. */
    @GetMapping("/dpd")
    public ApiResponse<DpdView> dpd(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
        int dpd = dpdCalculator.daysPastDue(dueDate, effectiveAsOf);
        return ApiResponse.ok(new DpdView(dueDate, effectiveAsOf, dpd, dpdCalculator.bucket(dpd)));
    }
}
