package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.text;
import static com.navix.verification.support.ProviderJson.trimmed;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.common.verification.ProviderFailureDetails;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.exception.TerminalVerificationException;
import com.navix.verification.exception.VerificationException;
import java.util.Locale;
import com.navix.verification.dto.SignzyDtos.PanRequest;
import com.navix.verification.dto.SignzyDtos.PanResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signzy PAN 206AB Compliance (individual search) — {@code POST /api/v3/pan/compliance-206-individual-search}.
 * A compliance-grade PAN check: masked name + operative status + PAN-Aadhaar link + 206AB specified-person
 * flag. The fields are returned at the TOP LEVEL (not wrapped in {@code result}). Note it does NOT return
 * DOB/gender/address — those are captured from the DigiLocker/Aadhaar step instead.
 */
@Component
public class SignzyPanClient {

    private static final String ENDPOINT = "/api/v3/pan/compliance-206-individual-search";

    private final RestClient signzy;

    public SignzyPanClient(@Qualifier(VerificationClientConfig.SIGNZY_PROD_CLIENT) RestClient signzy) {
        this.signzy = signzy;
    }

    public PanResponse verify(String pan) {
        // maskedName=false → Signzy adds the full name in `unMaskedName` (the account must be entitled).
        PanRequest request = new PanRequest(pan, "false");
        JsonNode root;
        try {
            root = post(signzy, ENDPOINT, request);
        } catch (VerificationException e) {
            throw panNotFoundOrRethrow(e);
        }
        return new PanResponse(
                text(root.path("number")),
                text(root.path("number")),
                trimmed(root.path("entityName")),
                trimmed(root.path("unMaskedName")),
                text(root.path("panAllotmentDate")),
                text(root.path("panAadhaarLinkStatus")),
                bool(root.path("compliant")),
                text(root.path("isSpecified")),
                text(root.path("panStatus")));
    }

    /**
     * Signzy answers a PAN that does not exist with
     * {@code 404 {"error":{"reason":"NOT_FOUND","message":"Pan Number Not Found"}}}. That is an
     * ANSWER, and a final one: of the 18 applications traced through the Sep-2026 audit that hit it,
     * not one ever produced a valid PAN afterwards. Left as a plain failure it fell through to Digitap
     * (412, a product we are not provisioned for) and then to Fintrix, which billed us to reply
     * "Invalid PAN" — 41 paid calls in the window.
     *
     * <p>The {@code safeDetail} check is load-bearing, not belt-and-braces. {@code reason} is not in
     * {@code ProviderJson}'s allow-listed code fields, so a 404 carries no {@code providerCode} to
     * match on, and a 404 from a mistyped base URL or a withdrawn route looks identical on status
     * alone — which would fail every borrower's PAN as "not found". Anything that is not explicitly a
     * not-found message keeps falling through as before.
     */
    private static VerificationException panNotFoundOrRethrow(VerificationException e) {
        String detail = e.safeDetail();
        if (Integer.valueOf(404).equals(e.httpStatus()) && detail != null
                && detail.toLowerCase(Locale.ROOT).contains("not found")) {
            return new TerminalVerificationException("Signzy: PAN not found", e, e.httpStatus(),
                    e.endpoint(), ProviderFailureDetails.PAN_NOT_FOUND, detail);
        }
        return e;
    }
}
