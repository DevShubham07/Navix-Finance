package com.navix.collections.controller;

import com.navix.collections.dto.CollectionsDtos.AssignOfficerRequest;
import com.navix.collections.dto.CollectionsDtos.CaseDetailView;
import com.navix.collections.dto.CollectionsDtos.CaseView;
import com.navix.collections.dto.CollectionsDtos.CollectionPaymentView;
import com.navix.collections.dto.CollectionsDtos.DpdView;
import com.navix.collections.dto.CollectionsDtos.InteractionView;
import com.navix.collections.dto.CollectionsDtos.LogInteractionRequest;
import com.navix.collections.dto.CollectionsDtos.OpenCaseRequest;
import com.navix.collections.dto.CollectionsDtos.ProposeSettlementRequest;
import com.navix.collections.dto.CollectionsDtos.RaiseCollectionPaymentRequest;
import com.navix.collections.dto.CollectionsDtos.SettlementView;
import com.navix.collections.dto.CollectionsDtos.ValidateCollectionPaymentRequest;
import com.navix.collections.entity.CollectionPaymentKind;
import com.navix.collections.entity.CollectionPaymentStatus;
import com.navix.collections.service.CollectionPaymentService;
import com.navix.collections.service.CollectionsService;
import com.navix.collections.service.DpdCalculator;
import com.navix.collections.service.SettlementService;
import com.navix.common.exception.BusinessException;
import com.navix.common.loan.LoanSummary;
import com.navix.common.staff.StaffSummary;
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
 * REST endpoints for collections: case management (bridged to real loans),
 * interaction logging, the maker-checker settlement workflow, the collections
 * payment chain (V47 — officer records, Accountant validates), and a live
 * DPD-bucket helper. All responses use {@link ApiResponse}.
 */
@RestController
@RequestMapping("/api/collections")
@RequiredArgsConstructor
public class CollectionsController {

    private final CollectionsService collectionsService;
    private final SettlementService settlementService;
    private final CollectionPaymentService collectionPaymentService;
    private final DpdCalculator dpdCalculator;

    /** Loans eligible to open a case against (ACTIVE/OVERDUE, due on or before {@code dueBy}). */
    @GetMapping("/loans")
    public ApiResponse<List<LoanSummary>> collectibleLoans(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueBy) {
        LocalDate asOf = dueBy != null ? dueBy : LocalDate.now();
        return ApiResponse.ok(collectionsService.collectibleLoans(asOf));
    }

    /** ACTIVE collections officers, for the assignee picker. */
    @GetMapping("/officers")
    public ApiResponse<List<StaffSummary>> assignableOfficers() {
        return ApiResponse.ok(collectionsService.assignableOfficers());
    }

    /** Open (or fetch) the collection case for a real loan, moving it into collections. */
    @PostMapping("/cases")
    public ApiResponse<CaseDetailView> openCase(@Valid @RequestBody OpenCaseRequest request) {
        return ApiResponse.ok(collectionsService.openCase(request.loanId()));
    }

    @GetMapping("/cases")
    public ApiResponse<List<CaseView>> listCases() {
        return ApiResponse.ok(collectionsService.listCaseViews());
    }

    @GetMapping("/cases/{caseId}")
    public ApiResponse<CaseDetailView> getCase(@PathVariable UUID caseId) {
        return ApiResponse.ok(collectionsService.getCaseDetail(caseId));
    }

    /** Assign a collections officer (a real ACTIVE executive) to a case. */
    @PostMapping("/cases/{caseId}/assign")
    public ApiResponse<CaseDetailView> assignOfficer(@PathVariable UUID caseId,
                                                     @Valid @RequestBody AssignOfficerRequest request) {
        collectionsService.assignOfficer(caseId, request.officerId());
        return ApiResponse.ok(collectionsService.getCaseDetail(caseId));
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
        return ApiResponse.ok(settlementService.propose(caseId, request.settlementAmountPaise()));
    }

    /** All settlements (pending + approved) for the collections settlements worklist. */
    @GetMapping("/settlements")
    public ApiResponse<List<SettlementView>> listSettlements() {
        return ApiResponse.ok(settlementService.listAll());
    }

    /** Collections Head approves a settlement (separation of duties enforced). */
    @PostMapping("/settlements/{settlementId}/approve")
    public ApiResponse<SettlementView> approveSettlement(@PathVariable UUID settlementId) {
        return ApiResponse.ok(settlementService.approve(settlementId));
    }

    /** Collections Head rejects a proposed settlement (separation of duties enforced). */
    @PostMapping("/settlements/{settlementId}/reject")
    public ApiResponse<SettlementView> rejectSettlement(@PathVariable UUID settlementId) {
        return ApiResponse.ok(settlementService.reject(settlementId));
    }

    // ---- collections payments (V47) ---------------------------------------------------

    /** A Collection Executive (or Head) records a payment the borrower made. */
    @PostMapping("/cases/{caseId}/payments")
    public ApiResponse<CollectionPaymentView> raisePayment(
            @PathVariable UUID caseId, @Valid @RequestBody RaiseCollectionPaymentRequest request) {
        CollectionPaymentKind kind;
        try {
            kind = CollectionPaymentKind.valueOf(request.kind());
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException("INVALID_PAYMENT_KIND",
                    "Payment kind must be PART_PAYMENT, FULL_PAYMENT or SETTLEMENT");
        }
        return ApiResponse.ok(collectionPaymentService.raise(caseId, kind, request.amountPaise(),
                request.paidOn(), request.txnRef(), request.proofRef(), request.settlementId()));
    }

    @GetMapping("/cases/{caseId}/payments")
    public ApiResponse<List<CollectionPaymentView>> listCasePayments(@PathVariable UUID caseId) {
        return ApiResponse.ok(collectionPaymentService.listForCase(caseId));
    }

    /**
     * Payments across all cases. {@code status} filters to a specific queue — the Accountant reads
     * {@code PENDING_ACCOUNTANT}, the Collection Head {@code PENDING_HEAD}.
     */
    @GetMapping("/payments")
    public ApiResponse<List<CollectionPaymentView>> listPayments(
            @RequestParam(required = false) String status) {
        if (status == null || status.isBlank()) {
            return ApiResponse.ok(collectionPaymentService.listAll());
        }
        try {
            return ApiResponse.ok(
                    collectionPaymentService.listByStatus(CollectionPaymentStatus.valueOf(status)));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException("INVALID_PAYMENT_STATUS", "Unknown payment status: " + status);
        }
    }

    /** Collection Head releases a settlement payment to the Accountant (SoD enforced). */
    @PostMapping("/payments/{paymentId}/head-approve")
    public ApiResponse<CollectionPaymentView> headApprovePayment(@PathVariable UUID paymentId) {
        return ApiResponse.ok(collectionPaymentService.headApprove(paymentId));
    }

    /** The Accountant validates the payment (crediting the loan) or rejects it with remarks. */
    @PostMapping("/payments/{paymentId}/validate")
    public ApiResponse<CollectionPaymentView> validatePayment(
            @PathVariable UUID paymentId, @Valid @RequestBody ValidateCollectionPaymentRequest request) {
        return ApiResponse.ok(
                collectionPaymentService.validate(paymentId, request.accept(), request.remarks()));
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
