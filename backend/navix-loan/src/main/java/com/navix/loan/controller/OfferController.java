package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.OfferDtos.ChooseAmountRequest;
import com.navix.loan.dto.OfferDtos.DisbursalAccountRequest;
import com.navix.loan.dto.OfferDtos.DisbursalAccountView;
import com.navix.loan.dto.OfferDtos.OfferSummaryView;
import com.navix.loan.dto.OfferDtos.ReferenceView;
import com.navix.loan.dto.OfferDtos.ReferencesRequest;
import com.navix.loan.dto.OfferDtos.SanctionLetterView;
import com.navix.loan.dto.OfferDtos.ManualEsignRequest;
import com.navix.loan.service.ApplicationFlowService;
import com.navix.loan.service.ApplicationVerificationService.StepResult;
import com.navix.loan.service.OfferService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The borrower's post-sanction offer journey (revamp.md Phase 3).
 *
 * <p>Borrower-only and ownership-checked, exactly like {@link ApplicationVerificationController} —
 * the identity steps inside this journey (DigiLocker, selfie, address) still live on that controller,
 * so the two authorize identically on purpose.
 */
@RestController
@RequestMapping("/api/applications/{id}/offer")
@RequiredArgsConstructor
public class OfferController {

    private final OfferService offer;
    private final ApplicationFlowService flow;

    @PostMapping("/amount")
    public ApiResponse<ApplicationView> chooseAmount(@PathVariable Long id,
                                                     @Valid @RequestBody ChooseAmountRequest req) {
        authorize(id);
        return ApiResponse.ok(ApplicationView.of(offer.chooseAmount(id, req.amountPaise())));
    }

    @GetMapping("/references")
    public ApiResponse<List<ReferenceView>> references(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(offer.references(id));
    }

    @PostMapping("/references")
    public ApiResponse<List<ReferenceView>> saveReferences(@PathVariable Long id,
                                                           @RequestBody ReferencesRequest req) {
        authorize(id);
        return ApiResponse.ok(offer.saveReferences(id, req.references()));
    }

    @GetMapping("/summary")
    public ApiResponse<OfferSummaryView> summary(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(offer.summary(id));
    }

    /** Renders the Key Fact Statement to S3 and returns a short-lived URL to read it. */
    @PostMapping("/sanction-letter")
    public ApiResponse<SanctionLetterView> sanctionLetter(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(offer.sanctionLetter(id));
    }

    @PostMapping("/esign")
    public ApiResponse<StepResult> esign(@PathVariable Long id, @RequestBody ManualEsignRequest req) {
        authorize(id);
        return ApiResponse.ok(offer.manualEsign(id, req));
    }

    @GetMapping("/disbursal-account")
    public ApiResponse<DisbursalAccountView> disbursalAccount(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(offer.disbursalAccount(id));
    }

    /** The last step of the journey: confirms the account and hands off to disbursement. */
    @PostMapping("/disbursal-account")
    public ApiResponse<ApplicationView> confirmDisbursalAccount(
            @PathVariable Long id, @Valid @RequestBody DisbursalAccountRequest req) {
        authorize(id);
        return ApiResponse.ok(ApplicationView.of(offer.confirmDisbursalAccount(id, req)));
    }

    /** Borrower-only + ownership: a BORROWER may act only on their own application; ADMIN on any. */
    private void authorize(Long id) {
        String role = ActorContext.get().role();
        if (!"BORROWER".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Borrower action required");
        }
        if ("BORROWER".equals(role)
                && !String.valueOf(flow.get(id).getCustomerId()).equals(ActorContext.get().id())) {
            throw new BusinessException("FORBIDDEN_OWNER", "Not your application");
        }
    }
}
