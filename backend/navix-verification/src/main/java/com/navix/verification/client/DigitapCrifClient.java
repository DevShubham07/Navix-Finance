package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.ref;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.DigitapDtos.CrifRequest;
import com.navix.verification.dto.DigitapDtos.CrifResponse;
import com.navix.verification.exception.VerificationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Digitap Credit Analytics CRIF — {@code POST /credit_analytics/v2/cf} (<b>svc</b> host). The SECOND
 * bureau try, between Fintrix (CRIF, primary) and {@link DigitapCreditClient} (Experian, last resort);
 * see {@code DigitapVerificationAdapter.pullBureau}.
 *
 * <p>Distinct from {@link DigitapCreditClient} in every dimension except the credentials: different
 * endpoint, different host (svc, not api), a different bureau (CRIF High Mark, not Experian), and no
 * OTP/consent/device-ip block at all.
 *
 * <p>The score sits at {@code result.result_json.parsed_data.B2C-REPORT.SCORE[0].VALUE}
 * ({@code NAME: "PERFORM CONSUMER 2.2"}). Only the score is extracted — the {@code B2C-REPORT}
 * envelope differs throughout from both Experian's {@code INProfileResponse} and Fintrix's CRIF
 * {@code credit_report}, so neither {@code ExperianFactsParser} nor {@code CrifHighmarkFactsParser}
 * can read it and brief facts stay null. {@code SignzyCrifClient} makes the same call for the same
 * reason. (The trap: {@code PRIMARY-ACCOUNTS-SUMMARY} exists in both CRIF variants with *different*
 * keys inside, so a mis-pointed parser returns silent nulls rather than failing.)
 *
 * <p>Result codes: {@code 101} success, {@code 102} no record, {@code 103} name not found against the
 * mobile. 102/103 are real answers, NOT failures — they are returned as {@code noRecord} so the
 * adapter does not fall through and burn a second billable Experian pull on a borrower the bureau
 * genuinely has nothing on.
 *
 * <p>Billable on {@code result_code 101} only, per the vendor spec.
 */
@Component
public class DigitapCrifClient {

    private static final String ENDPOINT = "/credit_analytics/v2/cf";

    /** Pass identity ourselves; "1" would invoke Digitap's separately-licensed Mobile to Prefill. */
    private static final String PREFILL_LOOKUP_OFF = "0";

    private static final int RESULT_OK = 101;
    private static final int RESULT_NO_RECORD = 102;
    private static final int RESULT_NAME_NOT_FOUND = 103;

    private final RestClient digitapSvc;

    public DigitapCrifClient(@Qualifier(VerificationClientConfig.DIGITAP_CRIF_CLIENT) RestClient digitapSvc) {
        this.digitapSvc = digitapSvc;
    }

    public CrifResponse pull(String pan, String name, String mobile, String dob, String clientRef) {
        String[] parts = splitName(name);
        JsonNode root = post(digitapSvc, ENDPOINT, new CrifRequest(
                ref(clientRef), mobile, PREFILL_LOOKUP_OFF, parts[0], parts[1], pan, dob));

        String txnId = text(root.path("request_id"));
        Integer resultCode = integer(root.path("result_code"));
        if (resultCode != null && (resultCode == RESULT_NO_RECORD || resultCode == RESULT_NAME_NOT_FOUND)) {
            return new CrifResponse(txnId, null, true, root.toString());
        }
        if (resultCode == null || resultCode != RESULT_OK) {
            // Anything that is neither a success nor a recognised no-hit is a genuine provider failure:
            // throw so the router falls through to Experian rather than recording a phantom no-record.
            String message = text(root.path("message"));
            throw new VerificationException("Digitap CRIF error: "
                    + (message == null || message.isBlank() ? "unspecified provider error" : message));
        }

        Integer score = integer(root.path("result").path("result_json").path("parsed_data")
                .path("B2C-REPORT").path("SCORE").path(0).path("VALUE"));
        return new CrifResponse(txnId, score, score == null, root.toString());
    }

    /** Same split the Experian client uses — Digitap wants first/last separately. */
    private static String[] splitName(String name) {
        if (name == null || name.isBlank()) {
            return new String[] {"DhanBoost", "."};
        }
        String trimmed = name.trim();
        int sp = trimmed.indexOf(' ');
        if (sp < 0) {
            return new String[] {trimmed, "."};
        }
        return new String[] {trimmed.substring(0, sp), trimmed.substring(sp + 1).trim()};
    }
}
