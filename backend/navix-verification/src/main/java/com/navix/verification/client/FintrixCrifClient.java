package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.postAllowingErrorEnvelope;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.verification.BureauReportFacts;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.FintrixDtos;
import com.navix.verification.dto.FintrixDtos.CrifRequest;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
import com.navix.verification.exception.VerificationException;
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
                : postAllowingErrorEnvelope(fintrix, ENDPOINT, new CrifRequest(name, mobile, remark, CONSENT));
        CrifResponse challenge = kbaChallenge(root);
        if (challenge != null) {
            return challenge;
        }
        rejectUnlessNoRecord(root);
        JsonNode data = root.path("canonical").path("data");
        return parse(data, root.toString(), name, mobile);
    }

    /**
     * CRIF can answer a real, existing report (note {@code report_id}) gated behind a
     * knowledge-based-authentication question instead of a score:
     * {@code {"status":"error","success":true,"error_message":"Unable to Authenticate, Please Solve
     * the Auth Questions","data":{"question":...,"options":[...],"order_id":...}}}. This is neither a
     * failure (don't throw — the router would burn a second billable Digitap call) nor a thin file
     * (don't call it noRecord — that flag means "no record exists" and would misclassify the backfill
     * row). Matched defensively on the error message AND the presence of {@code data.question} +
     * {@code data.answer_type}, so an unrelated error envelope never gets misread as a challenge.
     *
     * <p>Actually answering the question needs a Fintrix endpoint we have no documentation for — out
     * of scope. This only stops the retry loop and surfaces the question to staff.
     */
    private static CrifResponse kbaChallenge(JsonNode root) {
        if (!"error".equalsIgnoreCase(text(root.path("status")))) {
            return null;
        }
        String message = text(root.path("error_message"));
        JsonNode data = root.path("data");
        boolean looksLikeKba = message != null
                && message.toLowerCase(java.util.Locale.ROOT).contains("auth question")
                && !data.path("question").isMissingNode()
                && !data.path("answer_type").isMissingNode();
        if (!looksLikeKba) {
            return null;
        }
        java.util.List<String> options = new java.util.ArrayList<>();
        for (JsonNode option : data.path("options")) {
            String value = text(option);
            if (value != null) {
                options.add(value);
            }
        }
        FintrixDtos.Challenge fintrixChallenge = new FintrixDtos.Challenge(
                text(data.path("question")), options, text(data.path("order_id")));
        return new CrifResponse(text(data.path("report_id")), null, false, null,
                root.toString(), null, fintrixChallenge);
    }

    /**
     * Fintrix answers a thin file with HTTP 200 and an ERROR envelope —
     * {@code {"status":"error","success":true,"error_message":"No data found in CRIF,Please re-verify
     * details"}} — which is a real answer, not a failure. Observed in production 2026-08-23 on the
     * first backfill batch; it is the no-hit shape we had never captured.
     *
     * <p>Treating it as a failure is expensive and wrong three times over: the router burns a second
     * billable call falling through to Digitap, the backfill records FAILED, and FAILED is exactly
     * what a re-run retries — so a borrower with no credit history would be paid for again on every
     * pass and could never succeed. Let it through and {@link #parse} classifies it as a no-record
     * (no credit_report node, so noHit), which never reaches the auto-reject rule.
     *
     * <p>Any OTHER error envelope is still a genuine provider failure and must throw, so the chain
     * falls through as designed.
     */
    private static void rejectUnlessNoRecord(JsonNode root) {
        if (!"error".equalsIgnoreCase(text(root.path("status")))) {
            return;
        }
        String message = text(root.path("error_message"));
        if (message == null || !message.toLowerCase(java.util.Locale.ROOT).contains("no data found")) {
            throw new VerificationException("Fintrix crif_combine error: "
                    + (message == null || message.isBlank() ? "unspecified provider error" : message));
        }
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
