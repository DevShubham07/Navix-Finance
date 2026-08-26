package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.ref;
import static com.navix.verification.support.ProviderJson.text;
import static com.navix.verification.support.ProviderJson.trimmed;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.FintrixDtos.PanRequest;
import com.navix.verification.dto.FintrixDtos.PanResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fintrix PAN Comprehensive — {@code POST /pan_comprehensive}. The PAN FALLBACK behind Signzy (see
 * {@code RoutingVerificationPort}); richer than Signzy's 206AB search in that it also returns
 * DOB/gender/masked-Aadhaar/address, but it carries no 206AB compliance flags.
 *
 * <p>Fields arrive wrapped in {@code data} (address again inside {@code data.address}); the
 * transaction id is at the TOP level, not inside {@code data}.
 *
 * <p>Uses plain {@code post} rather than {@code postAllowingErrorEnvelope}: a rejected PAN comes back
 * as {@code {"status":"error","error_message":"Invalid PAN"}} at HTTP 200, and turning that into a
 * {@link com.navix.verification.exception.VerificationException} is exactly right — it makes the router
 * fall through to Digitap instead of accepting a non-answer.
 *
 * <p>Every call is live and billable — Fintrix has no sandbox (the {@code X-Sandbox} header is inert).
 */
@Component
public class FintrixPanClient {

    /**
     * Leading slash, matching {@code FintrixCrifClient}'s verified-working {@code "/crif_combine"}.
     * {@code ProviderCallCatalog} keys off this exact string, so the two must stay in step.
     */
    private static final String ENDPOINT = "/pan_comprehensive";

    private final RestClient fintrix;

    public FintrixPanClient(@Qualifier(VerificationClientConfig.FINTRIX_CLIENT) RestClient fintrix) {
        this.fintrix = fintrix;
    }

    public PanResponse verify(String pan, String clientRef) {
        JsonNode root = post(fintrix, ENDPOINT, new PanRequest(pan, ref(clientRef)));
        JsonNode data = root.path("data");
        JsonNode address = data.path("address");
        return new PanResponse(
                text(root.path("transaction_id")),
                text(data.path("status")),
                // Fintrix pads the name — a live response came back as "  SHUBHAM" — and this value
                // feeds the profile plus recomputeNameMatch, so it must not carry the padding.
                trimmed(data.path("full_name")),
                text(data.path("dob")),
                text(data.path("gender")),
                bool(data.path("aadhaar_linked")),
                text(data.path("masked_aadhaar")),
                text(data.path("pan_number")),
                text(data.path("doi")),
                text(address.path("state")),
                text(address.path("zip")));
    }
}
