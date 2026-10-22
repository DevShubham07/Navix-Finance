package com.navix.collections.controller;

import com.navix.collections.dto.CollectionsDtos.AssignOfficerRequest;
import com.navix.collections.dto.CollectionsDtos.CaseDetailView;
import com.navix.collections.dto.CollectionsDtos.CaseView;
import com.navix.collections.dto.CollectionsDtos.WorklistRow;
import com.navix.collections.dto.CollectionsDtos.CollectionPaymentView;
import com.navix.collections.dto.CollectionsDtos.DpdView;
import com.navix.collections.dto.CollectionsDtos.InteractionView;
import com.navix.collections.dto.CollectionsDtos.LogInteractionRequest;
import com.navix.collections.dto.CollectionsDtos.OpenCaseRequest;
import com.navix.collections.dto.CollectionsDtos.ProposeSettlementRequest;
import com.navix.collections.dto.CollectionsDtos.RaiseCollectionPaymentRequest;
import com.navix.collections.dto.CollectionsDtos.SettlementView;
import com.navix.collections.dto.CollectionsDtos.UpcomingLoanView;
import com.navix.collections.dto.CollectionsDtos.ValidateCollectionPaymentRequest;
import com.navix.collections.entity.CollectionPaymentKind;
import com.navix.collections.entity.CollectionPaymentStatus;
import com.navix.collections.service.CollectionPaymentService;
import com.navix.collections.service.CollectionsService;
import com.navix.collections.service.DpdCalculator;
import com.navix.collections.service.ManualDebitService;
import com.navix.common.loan.DebitDirectory;
import com.navix.collections.service.SettlementService;
import com.navix.common.exception.BusinessException;
import com.navix.common.loan.LoanSummary;
import com.navix.common.query.SqlFacets;
import com.navix.common.staff.StaffSummary;
import com.navix.common.web.ApiResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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

    /**
     * Defaults for an omitted date parameter are IST, not the container's UTC clock — a bare
     * {@code LocalDate.now()} reports yesterday between 00:00 and 05:30 IST. Same reason and same
     * convention as {@code CollectionsService}.
     */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final CollectionsService collectionsService;
    private final SettlementService settlementService;
    private final CollectionPaymentService collectionPaymentService;
    private final DpdCalculator dpdCalculator;
    private final ManualDebitService manualDebitService;

    /**
     * The collections worklist — one row per live loan, past due or due within the next week,
     * whether or not a case has been opened on it. This is what the DPD bucket pages render.
     */
    @GetMapping("/worklist")
    public ApiResponse<List<WorklistRow>> worklist(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.ok(collectionsService.worklist(asOf));
    }

    /**
     * One page of the worklist, for the register that pages server-side. {@code q} searches (id, name,
     * mobile, PAN, employer, officer, loan #); {@code from}/{@code to} window the DUE date; {@code bucket}
     * is a {@code DpdBucket} name (omit for all); {@code f.<column>} are the column filters; {@code sort}
     * and {@code dir} order it; {@code page} is 1-indexed. Scope (an executive sees only their cases)
     * is applied server-side. The whole-list {@link #worklist} stays for the bucket cards and the
     * sidebar badge, which need every row's bucket and balance.
     */
    @GetMapping("/worklist/page")
    public ApiResponse<CollectionsService.WorklistPage> worklistPage(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam Map<String, String> all) {
        return ApiResponse.ok(collectionsService.worklistPage(q, from, to, bucket, asOf, page, size, sort, dir, all));
    }

    /** The bucket cards: count and derived balance per DPD bucket, in the caller's scope, unfiltered. */
    @GetMapping("/worklist/summary")
    public ApiResponse<List<CollectionsService.BucketSummary>> worklistSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.ok(collectionsService.worklistSummary(asOf));
    }

    /**
     * The distinct values one column's filter checklist can offer, inside the caller's scope and under
     * the OTHER columns' {@code f.*} filters (the column's own is ignored so its dropdown does not collapse).
     */
    @GetMapping("/worklist/facets")
    public ApiResponse<SqlFacets.Facet> worklistFacets(
            @RequestParam("column") String column,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam Map<String, String> all) {
        return ApiResponse.ok(collectionsService.worklistFacet(column, q, from, to, bucket, asOf, all));
    }

    /** Loans eligible to open a case against (ACTIVE/OVERDUE, due on or before {@code dueBy}). */
    @GetMapping("/loans")
    public ApiResponse<List<LoanSummary>> collectibleLoans(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueBy) {
        LocalDate asOf = dueBy != null ? dueBy : LocalDate.now(IST);
        return ApiResponse.ok(collectionsService.collectibleLoans(asOf));
    }

    /**
     * The pre-due watchlist for the UPCOMING DPD bucket: live loans not yet due, soonest first.
     * Read-only -- unlike {@link #collectibleLoans}, which feeds a picker that opens cases, nothing
     * reached from here writes or moves a loan into collections.
     */
    @GetMapping("/upcoming")
    public ApiResponse<List<UpcomingLoanView>> upcoming(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.ok(collectionsService.upcomingWatchlist(asOf));
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

    /**
     * The collection case for a real loan, looked up by loan id rather than the case's own UUID —
     * a staff loan detail view knows the loan id, not the case id. Same {@link CaseDetailView}
     * shape as {@link #getCase}. A loan with no case (the normal state for a healthy loan) is a
     * 404, not an error.
     */
    @GetMapping("/cases/by-loan/{loanId}")
    public ApiResponse<CaseDetailView> getCaseByLoan(@PathVariable Long loanId) {
        return ApiResponse.ok(collectionsService.getCaseDetailByLoanId(loanId));
    }

    /**
     * Present a NACH debit now. Moves REAL money. Body {@code amountPaise} omitted = full outstanding;
     * a value may only be lower. Guards live in the loan module; role, assignment and flag in the service.
     */
    @PostMapping("/loans/{loanId}/debit")
    public ApiResponse<DebitDirectory.Result> debit(@PathVariable Long loanId,
                                                    @RequestBody(required = false) Map<String, Long> body) {
        return ApiResponse.ok(manualDebitService.debit(loanId, body == null ? null : body.get("amountPaise")));
    }

    /** Every NACH debit presented on this loan, newest attempt first. Read-only; no provider text. */
    @GetMapping("/loans/{loanId}/debits")
    public ApiResponse<java.util.List<DebitDirectory.DebitRecord>> debits(@PathVariable Long loanId) {
        return ApiResponse.ok(manualDebitService.history(loanId));
    }

    /** Re-read one debit's outcome from the provider ("Check status"). */
    @PostMapping("/loans/{loanId}/debits/{debitId}/reconcile")
    public ApiResponse<DebitDirectory.DebitRecord> reconcileDebit(@PathVariable Long loanId, @PathVariable Long debitId) {
        return ApiResponse.ok(manualDebitService.reconcile(loanId, debitId));
    }

    /** Cancel one in-flight debit at the provider; refused once the bank has picked it up. */
    @PostMapping("/loans/{loanId}/debits/{debitId}/cancel")
    public ApiResponse<DebitDirectory.DebitRecord> cancelDebit(@PathVariable Long loanId, @PathVariable Long debitId) {
        return ApiResponse.ok(manualDebitService.cancel(loanId, debitId));
    }

    /** The refund register: debits that collected money a borrower no longer owed. COLLECTION_HEAD/ADMIN. */
    @GetMapping("/debits/overpayments")
    public ApiResponse<java.util.List<DebitDirectory.DebitRecord>> overpayments() {
        return ApiResponse.ok(manualDebitService.overpayments());
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
        LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now(IST);
        int dpd = dpdCalculator.daysPastDue(dueDate, effectiveAsOf);
        return ApiResponse.ok(new DpdView(dueDate, effectiveAsOf, dpd, dpdCalculator.bucket(dpd)));
    }
}
