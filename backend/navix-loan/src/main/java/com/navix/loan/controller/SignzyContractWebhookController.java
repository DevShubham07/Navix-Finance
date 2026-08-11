package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.service.ApplicationVerificationService;
import com.navix.loan.service.OfferService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signzy's contract callback — an <em>accelerator only</em>.
 *
 * <p>The borrower's own poll after the redirect is what normally finalises a signature, and our
 * verification row is the source of truth. This exists for the case the poll cannot cover: the borrower
 * signs and then closes the tab before being redirected back. It re-enters exactly the same path the
 * poll would (`OfferService.esignStatus`), which re-reads the contract from Signzy rather than trusting
 * anything in the callback body — so a forged or replayed callback can only cause a re-read.
 *
 * <p>Unauthenticated at the filter chain (a provider has no session), so it authenticates itself against
 * the shared secret we handed Signzy as {@code callbackUrlAuthorizationHeader}. It always answers 200:
 * a provider retrying a callback we've already handled, or one for an application we no longer track, is
 * not an error worth surfacing to them.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks/signzy")
@RequiredArgsConstructor
public class SignzyContractWebhookController {

    private final OfferService offer;
    private final ApplicationVerificationRepository verificationRepo;

    @Value("${navix.esign.callback-secret:}")
    private String callbackSecret;

    @PostMapping("/contract")
    public ApiResponse<Map<String, Object>> contract(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {

        if (callbackSecret == null || callbackSecret.isBlank()
                || !callbackSecret.equals(authorization)) {
            // Say nothing about why — an unauthenticated caller learns nothing from us.
            log.warn("Rejected Signzy contract callback with a bad or missing secret");
            return ApiResponse.ok(Map.of("accepted", false));
        }
        String contractId = body != null ? String.valueOf(body.get("contractId")) : null;
        if (contractId == null || contractId.isBlank() || "null".equals(contractId)) {
            return ApiResponse.ok(Map.of("accepted", false));
        }
        List<ApplicationVerification> rows = verificationRepo
                .findByCheckTypeAndProviderTxnIdStartingWith(
                        ApplicationVerificationService.ESIGN, contractId);
        if (rows.isEmpty()) {
            log.info("Signzy contract callback for an unknown contract");
            return ApiResponse.ok(Map.of("accepted", false));
        }
        Long appId = rows.get(0).getApplicationId();
        try {
            offer.esignStatus(appId);
        } catch (RuntimeException resolveFailure) {
            // Never fail a callback: the borrower's own poll is the path that has to work.
            log.warn("Signzy contract callback could not resolve application {}: {}",
                    appId, resolveFailure.toString());
            return ApiResponse.ok(Map.of("accepted", false));
        }
        return ApiResponse.ok(Map.of("accepted", true));
    }
}
