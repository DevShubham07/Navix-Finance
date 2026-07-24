package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.SignzyDtos.Consent;
import com.navix.verification.dto.SignzyDtos.CrifRequest;
import com.navix.verification.dto.SignzyDtos.CrifResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signzy CRIF High Mark bureau pull — {@code POST /api/v3/bureau/crif} — the FALLBACK bureau when the
 * Experian pull misses. All identity fields are mandatory. Response shape is CRIF's
 * {@code data.crifReport.INDV-REPORT-FILE.INDV-REPORTS[].INDV-REPORT.SCORES[].SCORE-VALUE} — a different
 * layout from Experian, so no categorized brief facts are produced (facts stay {@code null}).
 */
@Component
public class SignzyCrifClient {

    private static final String ENDPOINT = "/api/v3/bureau/crif";

    private final RestClient signzy;

    public SignzyCrifClient(@Qualifier(VerificationClientConfig.SIGNZY_CLIENT) RestClient signzy) {
        this.signzy = signzy;
    }

    public CrifResponse pull(String pan, String name, String mobile, String dob) {
        String[] parts = splitName(name);
        Consent consent = new Consent(true, System.currentTimeMillis(), "0.0.0.0", "CM_1");
        JsonNode root = post(signzy, ENDPOINT,
                new CrifRequest(mobile, pan, parts[0], parts[1], dob, "", "NA", "", consent));
        JsonNode indvReport = root.path("data").path("crifReport")
                .path("INDV-REPORT-FILE").path("INDV-REPORTS")
                .path(0).path("INDV-REPORT");
        Integer score = integer(indvReport.path("SCORES").path(0).path("SCORE-VALUE"));
        return new CrifResponse(null, score, score == null);
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
