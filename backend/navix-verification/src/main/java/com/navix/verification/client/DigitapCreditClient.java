package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.ref;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.common.verification.BureauReportFacts;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.DigitapDtos.CreditRequest;
import com.navix.verification.dto.DigitapDtos.CreditResponse;
import com.navix.verification.support.ExperianFactsParser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Digitap Credit Analytics — {@code POST /credit_analytics/request} (api host). The FALLBACK bureau
 * (Experian). The report body is Experian's {@code INProfileResponse} at
 * {@code result.result_json.INProfileResponse} — the same shape {@link ExperianFactsParser} reads — with
 * the score at {@code SCORE.BureauScore}.
 *
 * <p>Note: Digitap's live Credit Analytics requires OTP-based consent (an {@code otp} the borrower
 * receives). This fallback sends the consent block with the fields it has; a real production pull would
 * need the collected OTP threaded in.
 */
@Component
public class DigitapCreditClient {

    private static final String ENDPOINT = "/credit_analytics/request";
    private static final String CONSENT_MESSAGE =
            "I hereby authorize the pull of my credit report for loan evaluation.";

    private final RestClient digitapApi;

    public DigitapCreditClient(@Qualifier(VerificationClientConfig.DIGITAP_API_CLIENT) RestClient digitapApi) {
        this.digitapApi = digitapApi;
    }

    public CreditResponse pull(String pan, String name, String mobile, String dob, String clientRef) {
        String[] parts = splitName(name);
        CreditRequest req = new CreditRequest(
                ref(clientRef), mobile, 0, parts[0], parts[1], pan, dob,
                CONSENT_MESSAGE, "Yes", "server", "",
                String.valueOf(System.currentTimeMillis()), "0.0.0.0", "navix");
        JsonNode root = post(digitapApi, ENDPOINT, req);
        JsonNode report = root.path("result").path("result_json").path("INProfileResponse");
        Integer score = integer(report.path("SCORE").path("BureauScore"));
        Integer resultCode = integer(root.path("result_code"));
        boolean noRecord = score == null || (resultCode != null && resultCode == 103);
        BureauReportFacts facts = ExperianFactsParser.parse(report, score, name, pan, mobile);
        String txnId = text(root.path("request_id"));
        return new CreditResponse(txnId, score, noRecord, facts);
    }

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
