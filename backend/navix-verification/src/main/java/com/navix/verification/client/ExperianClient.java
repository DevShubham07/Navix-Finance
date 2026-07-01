package com.navix.verification.client;

import static com.navix.verification.support.FintrixJson.integer;
import static com.navix.verification.support.FintrixJson.post;
import static com.navix.verification.support.FintrixJson.ref;
import static com.navix.verification.support.FintrixJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.verification.BureauReportFacts;
import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.dto.FintrixDtos.ExperianRequest;
import com.navix.verification.dto.FintrixDtos.ExperianResponse;
import com.navix.verification.exception.VerificationException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Experian credit bureau pull via Fintrix ({@code individual_experian}). PRIMARY bureau for NAVIX
 * risk scoring. Besides the score, this harvests the full report into a provider-neutral
 * {@link BureauReportFacts} (Categories A/B/C) so the loan module can build the credit brief +
 * recommendation rating.
 *
 * <p>Sandbox responses are typically thin-file (no CAIS detail), so {@code facts} is {@code null}
 * there. For local end-to-end demos set {@code navix.bureau.fixture} (env {@code NAVIX_BUREAU_FIXTURE})
 * to a bundled report JSON (e.g. {@code classpath:samplepan.json}) — every pull then returns it,
 * yielding a rich brief without hitting Fintrix.
 */
@Component
public class ExperianClient {

    private static final String ENDPOINT = "individual_experian";

    private final RestClient fintrix;
    private final ObjectMapper objectMapper;
    private final String fixturePath;

    public ExperianClient(@Qualifier(FintrixClientConfig.FINTRIX_CLIENT) RestClient fintrix,
                          ObjectMapper objectMapper,
                          @Value("${navix.bureau.fixture:}") String fixturePath) {
        this.fintrix = fintrix;
        this.objectMapper = objectMapper;
        this.fixturePath = fixturePath;
    }

    /** Pull the Experian credit report for the customer (or the configured fixture, for demo). */
    public ExperianResponse pull(String pan, String name, String mobile, String clientRef) {
        JsonNode root = (fixturePath != null && !fixturePath.isBlank())
                ? loadFixture()
                : post(fintrix, ENDPOINT, new ExperianRequest(pan, name, mobile, "Y", ref(clientRef)));
        return parse(root);
    }

    private ExperianResponse parse(JsonNode root) {
        JsonNode data = root.path("data");
        Integer creditScore = integer(data.path("credit_score"));
        if (creditScore == null) {
            creditScore = integer(data.path("credit_report").path("SCORE").path("FCIREXScore"));
        }
        String message = text(root.path("message"));
        boolean noRecord = creditScore == null
                || (message != null && message.toLowerCase().contains("no record"));
        BureauReportFacts facts = parseFacts(data, creditScore);
        return new ExperianResponse(
                text(root.path("transaction_id")),
                text(root.path("status")),
                creditScore,
                noRecord,
                message,
                facts);
    }

    /**
     * Map the spec's Category A/B/C fields out of {@code data} into the neutral facts record. Returns
     * {@code null} for a thin-file response (no {@code credit_report} / no CAIS summary) so callers can
     * degrade gracefully. All bureau values are strings → parsed defensively (blank → null).
     */
    private BureauReportFacts parseFacts(JsonNode data, Integer creditScore) {
        JsonNode report = data.path("credit_report");
        if (report.isMissingNode() || report.isNull()) {
            return null;
        }
        JsonNode appDetails = report.path("Current_Application").path("Current_Application_Details");
        JsonNode customer = appDetails.path("Current_Applicant_Details");
        JsonNode addr = appDetails.path("Current_Applicant_Address_Details");
        JsonNode summary = report.path("CAIS_Account").path("CAIS_Summary");
        JsonNode creditAcct = summary.path("Credit_Account");
        JsonNode bal = summary.path("Total_Outstanding_Balance");
        // Thin-file: score may be present but no account/balance detail to brief on.
        if (creditAcct.isMissingNode() && bal.isMissingNode()) {
            return null;
        }
        return new BureauReportFacts(
                text(data.path("name")),
                text(data.path("pan")),
                text(data.path("mobile")),
                formatDob(text(customer.path("Date_Of_Birth_Applicant"))),
                text(addr.path("City")),
                text(addr.path("PINCode")),
                creditScore,
                integer(creditAcct.path("CreditAccountTotal")),
                integer(creditAcct.path("CreditAccountActive")),
                integer(creditAcct.path("CreditAccountClosed")),
                integer(creditAcct.path("CreditAccountDefault")),
                lng(bal.path("Outstanding_Balance_All")),
                lng(bal.path("Outstanding_Balance_Secured")),
                lng(bal.path("Outstanding_Balance_UnSecured")),
                integer(report.path("TotalCAPS_Summary").path("TotalCAPSLast30Days")),
                text(report.path("CreditProfileHeader").path("ReportNumber")));
    }

    /** Null-safe Long from a numeric JSON value or numeric string; else {@code null}. */
    private static Long lng(JsonNode node) {
        String t = text(node);
        if (t == null || t.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(t.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code "19850710"} → {@code "1985-07-10"}; anything not 8 digits is returned unchanged. */
    private static String formatDob(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.length() == 8 && t.chars().allMatch(Character::isDigit)) {
            return t.substring(0, 4) + "-" + t.substring(4, 6) + "-" + t.substring(6, 8);
        }
        return t.isBlank() ? null : t;
    }

    /** Load the demo fixture report from {@code classpath:} or the filesystem. */
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
