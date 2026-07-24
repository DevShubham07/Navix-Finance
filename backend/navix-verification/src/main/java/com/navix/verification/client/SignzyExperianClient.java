package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.verification.BureauReportFacts;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.SignzyDtos.Consent;
import com.navix.verification.dto.SignzyDtos.ExperianRequest;
import com.navix.verification.dto.SignzyDtos.ExperianResponse;
import com.navix.verification.exception.VerificationException;
import com.navix.verification.support.ExperianFactsParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signzy Experian-Lite bureau pull — {@code POST /api/v3/bureau/experian-lite}. PRIMARY bureau.
 * Signzy returns the standard Experian report at {@code data.jsonExperianReport}, which is the same
 * shape DhanBoost already parses ({@code SCORE.FCIREXScore}, CAIS summary, CAPS), so the neutral
 * {@link BureauReportFacts} come straight out of {@link ExperianFactsParser}.
 *
 * <p>For local end-to-end demos set {@code navix.bureau.fixture} (env {@code NAVIX_BUREAU_FIXTURE}) to a
 * bundled report JSON (e.g. {@code classpath:samplepan.json}); every pull then returns it. The fixture is
 * Fintrix-shaped ({@code data.credit_report}), so the report-node/score resolution below tolerates both
 * the real ({@code jsonExperianReport}) and fixture ({@code credit_report}) layouts.
 */
@Component
public class SignzyExperianClient {

    private static final String ENDPOINT = "/api/v3/bureau/experian-lite";

    private final RestClient signzy;
    private final ObjectMapper objectMapper;
    private final String fixturePath;

    public SignzyExperianClient(@Qualifier(VerificationClientConfig.SIGNZY_CLIENT) RestClient signzy,
                                ObjectMapper objectMapper,
                                @Value("${navix.bureau.fixture:}") String fixturePath) {
        this.signzy = signzy;
        this.objectMapper = objectMapper;
        this.fixturePath = fixturePath;
    }

    public ExperianResponse pull(String pan, String name, String mobile, String dob) {
        JsonNode root = (fixturePath != null && !fixturePath.isBlank())
                ? loadFixture()
                : post(signzy, ENDPOINT, buildRequest(pan, name, mobile, dob));
        return parse(root, name, pan, mobile);
    }

    private static ExperianRequest buildRequest(String pan, String name, String mobile, String dob) {
        String[] parts = splitName(name);
        Consent consent = new Consent(true, System.currentTimeMillis(), "0.0.0.0", "CM_1");
        return new ExperianRequest(mobile, pan, parts[0], parts[1], dob, consent);
    }

    private ExperianResponse parse(JsonNode root, String name, String pan, String mobile) {
        JsonNode data = root.path("data");
        // Real Signzy → data.jsonExperianReport; demo fixture → data.credit_report.
        JsonNode report = firstPresent(data.path("jsonExperianReport"), data.path("credit_report"));
        Integer score = integer(report.path("SCORE").path("FCIREXScore"));
        if (score == null) {
            score = integer(data.path("credit_score"));
        }
        String message = text(root.path("message"));
        boolean noRecord = score == null
                || (message != null && message.toLowerCase().contains("no record"));
        BureauReportFacts facts = ExperianFactsParser.parse(report, score, name, pan, mobile);
        String txnId = text(report.path("CreditProfileHeader").path("ReportNumber"));
        return new ExperianResponse(txnId, score, noRecord, facts);
    }

    private static JsonNode firstPresent(JsonNode a, JsonNode b) {
        return (a != null && !a.isMissingNode() && !a.isNull()) ? a : b;
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

    // ---- fixture loading (mirrors the retired ExperianClient) ----

    private JsonNode loadFixture() {
        String p = fixturePath.trim();
        try {
            if (p.startsWith("classpath:")) {
                return readClasspath(p.substring("classpath:".length()));
            }
            File f = new File(p);
            if (f.isFile()) {
                return objectMapper.readTree(f);
            }
            JsonNode cp = readClasspathOrNull(p);
            if (cp != null) {
                return cp;
            }
            throw new VerificationException("Bureau fixture not found: " + p);
        } catch (IOException e) {
            throw new VerificationException("Failed to read bureau fixture " + p, e);
        }
    }

    private JsonNode readClasspath(String name) throws IOException {
        JsonNode n = readClasspathOrNull(name);
        if (n == null) {
            throw new VerificationException("Bureau fixture not on classpath: " + name);
        }
        return n;
    }

    private JsonNode readClasspathOrNull(String name) throws IOException {
        String resource = name.startsWith("/") ? name : "/" + name;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return in == null ? null : objectMapper.readTree(in);
        }
    }
}
