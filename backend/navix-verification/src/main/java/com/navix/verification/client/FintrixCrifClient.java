package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.verification.BureauReportFacts;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.FintrixDtos.CrifRequest;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
import com.navix.verification.support.BureauFixtureLoader;
import com.navix.verification.support.CrifHighmarkFactsParser;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fintrix CRIF Highmark bureau pull — {@code POST /crif_combine}. The bureau PRIMARY (Digitap Credit
 * Analytics is now the fallback; Signzy's Experian/CRIF legs are retired from the routing chain — see
 * {@code SignzyVerificationAdapter}). Keyed on name + mobile only — Fintrix has no PAN field on this
 * endpoint; it hands back a PAN/DOB in the report body for the caller to cross-check against the
 * verified KYC identity (that check lives in {@code ApplicationVerificationService}, not here).
 *
 * <p>Envelope: {@code {success, canonical:{timestamp, transaction_id, status, data:{name, mobile,
 * credit_report, credit_report_link}}, is_sandbox, request_id, transaction_id}}. Score lives at
 * {@code credit_report.SCORES.SCORE.SCORE-VALUE} (a numeric string); the report id at
 * {@code credit_report.HEADER.REPORT-ID}.
 *
 * <p>For local end-to-end demos set {@code navix.bureau.fixture} (env {@code NAVIX_BUREAU_FIXTURE}) —
 * the SAME property {@link SignzyExperianClient} honours, so every offline environment (local dev, CI,
 * the demo seed) keeps a bureau result regardless of which provider is primary. Its value is used only
 * as an on/off toggle here: Experian and CRIF are different shapes, so this client always serves its
 * OWN bundled CRIF-shaped sample ({@code crif-combine-sample.json}, a redacted real response — see
 * {@code docs/fintrix/crif-combine-sample.json}) rather than whatever path the toggle happens to name.
 * That bundled file is the FULL live envelope (not a stand-in for the unwrapped {@code canonical} node),
 * so fixture and live share the exact same unwrap path below.
 */
@Component
public class FintrixCrifClient {

    private static final Logger log = LoggerFactory.getLogger(FintrixCrifClient.class);
    private static final String ENDPOINT = "/crif_combine";
    private static final String CONSENT = "yes";
    private static final String BUNDLED_FIXTURE = "classpath:crif-combine-sample.json";

    /**
     * We have never observed a real no-hit response from this endpoint — the no-hit branch below is
     * defensive. Flip once so the FIRST occurrence logs the full envelope at WARN (to pin down the real
     * shape), without spamming the log on every subsequent one.
     */
    private static final AtomicBoolean NO_HIT_LOGGED = new AtomicBoolean(false);

    private final RestClient fintrix;
    private final ObjectMapper objectMapper;
    private final String fixturePath;

    public FintrixCrifClient(@Qualifier(VerificationClientConfig.FINTRIX_CLIENT) RestClient fintrix,
                             ObjectMapper objectMapper,
                             @Value("${navix.bureau.fixture:}") String fixturePath) {
        this.fintrix = fintrix;
        this.objectMapper = objectMapper;
        this.fixturePath = fixturePath;
    }

    /** {@code remark} is the caller's client reference (an application ref); {@code consent} is fixed. */
    public CrifResponse pull(String name, String mobile, String remark) {
        JsonNode root = (fixturePath != null && !fixturePath.isBlank())
                ? BureauFixtureLoader.load(objectMapper, BUNDLED_FIXTURE)
                : post(fintrix, ENDPOINT, new CrifRequest(name, mobile, remark, CONSENT));
        JsonNode data = root.path("canonical").path("data");
        return parse(data, root.toString(), name, mobile);
    }

    private CrifResponse parse(JsonNode data, String rawResponseJson, String name, String mobile) {
        JsonNode report = data.path("credit_report");
        Integer score = integer(report.path("SCORES").path("SCORE").path("SCORE-VALUE"));
        String link = text(data.path("credit_report_link"));

        // No-hit rule: a missing/blank/non-numeric score, or no credit_report at all, is a real "no
        // record" answer — never let a null score reach the auto-reject rule downstream.
        boolean noHit = report.isMissingNode() || report.isNull() || score == null;
        if (noHit) {
            if (NO_HIT_LOGGED.compareAndSet(false, true)) {
                log.warn("Fintrix crif_combine no-hit branch fired for the first time — full envelope: {}",
                        rawResponseJson);
            }
            return new CrifResponse(text(report.path("HEADER").path("REPORT-ID")), null, true,
                    null, rawResponseJson, link);
        }

        BureauReportFacts facts = CrifHighmarkFactsParser.parse(report, score, name, null, mobile);
        String txnId = text(report.path("HEADER").path("REPORT-ID"));
        return new CrifResponse(txnId, score, false, facts, rawResponseJson, link);
    }
}
