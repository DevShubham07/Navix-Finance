package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.SignzyProperties;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.SignzyDtos.BsaAccount;
import com.navix.verification.dto.SignzyDtos.BsaAnalyzeRequest;
import com.navix.verification.dto.SignzyDtos.BsaDownloadRequest;
import com.navix.verification.dto.SignzyDtos.BsaFile;
import com.navix.verification.dto.SignzyDtos.BsaReport;
import com.navix.verification.dto.SignzyDtos.BsaReportFiles;
import com.navix.verification.dto.SignzyDtos.BsaReportRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signzy Bank Statement Analysis — the three calls of the statement-analysis flow:
 * {@code POST /api/v3/statementanalysis/analyze-statement} &rarr; {@code /retrieve-work-order} &rarr;
 * {@code /download-report}.
 *
 * <p><b>Asynchronous and billable.</b> analyze-statement returns a {@code reportId} in status
 * {@code IN PROGRESS}; the numbers only exist once retrieve-work-order reports {@code ANALYSED}, and
 * download-report then hands back the JSON/Excel report URLs. Nothing in the lifecycle calls this: a
 * Credit Executive, Credit Head or ADMIN triggers it per customer from the Banking tab. Like
 * {@link DigitapSkipTraceClient} it deliberately bypasses {@code VerificationPort} — one vendor, no
 * fallback, and a flow that spans three calls rather than one capability lookup.
 *
 * <p><b>On the PRODUCTION account.</b> BSA is entitled on {@code api.signzy.app} — verified 2026-10-16:
 * the prod token reaches payload validation ({@code 400 "At least one fileUrl is required"}), while the
 * preproduction account answers {@code 403 "No remaining API credits"} to the same call. So this uses
 * {@code signzyProdRestClient}, like {@link SignzyPanClient}, not the preprod bean the other
 * capabilities share.
 *
 * <p><b>The body token.</b> The portal docs mark an {@code authToken} — a short-lived JWT from Signzy's
 * Authenticate API — mandatory in the BODY of every BSA call, separate from the header token. That same
 * probe got past the gateway with no body token at all, so a blank {@code navix.signzy.bsa-auth-token}
 * is NOT a precondition here: it is sent when configured and Signzy is left to judge. A 401 then says
 * plainly that it is required, and the audit row records it — better than refusing to call on an
 * assumption that may be wrong.
 *
 * <p>Response bodies are kept verbatim ({@code rawJson}) because the console renders Signzy's own
 * structure and the report schema is template-driven. Spec: Signzy API portal &rarr; API Catalog
 * &rarr; Bank Statement Analysis, read 2026-10-16.
 */
@Component
public class SignzyBankStatementClient {

    private static final String ANALYZE = "/api/v3/statementanalysis/analyze-statement";
    private static final String WORK_ORDER = "/api/v3/statementanalysis/retrieve-work-order";
    private static final String DOWNLOAD = "/api/v3/statementanalysis/download-report";

    /** Signzy's wording for "both formats" (see the portal's sample curl). */
    private static final String BOTH_FORMATS = "excel and json";

    /** {@code report.reportStatus} once every account has been analysed. */
    public static final String ANALYSED = "ANALYSED";
    /** {@code report.reportStatus} while Signzy is still working. */
    public static final String IN_PROGRESS = "IN PROGRESS";

    private final RestClient signzy;
    private final SignzyProperties props;

    public SignzyBankStatementClient(
            @Qualifier(VerificationClientConfig.SIGNZY_PROD_CLIENT) RestClient signzy,
            SignzyProperties props) {
        this.signzy = signzy;
        this.props = props;
    }

    /** Omitted from the request entirely when unset — never sent as an empty string. */
    private String authToken() {
        return blankToNull(props.bsaAuthToken());
    }

    /**
     * Submit statements for analysis. Returns immediately with the report in {@code IN PROGRESS}.
     *
     * @param files          presigned/public statement URLs, each with the borrower's file password
     *                       when there is one.
     * @param customerName   matched against the name on the statement; optional.
     * @param statementPeriod e.g. {@code "6m"}; optional.
     */
    public BsaReport analyze(List<BsaFile> files, String customerName, String statementPeriod) {
        BsaAnalyzeRequest request = new BsaAnalyzeRequest(
                authToken(), files, blankToNull(customerName), blankToNull(statementPeriod));
        return report(post(signzy, ANALYZE, request));
    }

    /** Progress of a submitted report — the same envelope, filled in further. */
    public BsaReport retrieveWorkOrder(String reportId) {
        return report(post(signzy, WORK_ORDER, new BsaReportRequest(authToken(), reportId)));
    }

    /** The finished report as downloadable JSON + Excel. Only meaningful once {@link #ANALYSED}. */
    public BsaReportFiles downloadReport(String reportId) {
        JsonNode root = post(signzy, DOWNLOAD,
                new BsaDownloadRequest(authToken(), reportId, BOTH_FORMATS));
        JsonNode result = root.path("result");
        return new BsaReportFiles(text(result.path("json")), text(result.path("excel")), root.toString());
    }

    /**
     * analyze-statement and retrieve-work-order share one envelope. They differ only in where the
     * per-file details hang: {@code fileDetails.success[]} on the first call, {@code files[]} on the
     * second — so both are read, and whichever is present supplies the transaction count and the
     * tampering verdict.
     */
    private static BsaReport report(JsonNode root) {
        JsonNode report = root.path("report");
        return new BsaReport(
                text(report.path("reportId")),
                text(report.path("reportStatus")),
                text(report.path("reportStatusDescription")),
                text(report.path("bankScore")),
                accountsOf(root),
                root.toString());
    }

    /**
     * The accounts in a stored envelope. Public because the console re-reads them from the row it
     * kept rather than from a second copy of the same data in our own columns.
     */
    public static List<BsaAccount> accountsOf(JsonNode root) {
        List<BsaAccount> accounts = new ArrayList<>();
        for (JsonNode entry : root.path("reportAccounts")) {
            JsonNode a = entry.path("account");
            JsonNode files = entry.has("files") ? entry.path("files") : entry.path("fileDetails").path("success");
            Integer txns = null;
            String authenticity = null;
            for (JsonNode f : files) {
                Integer n = integer(f.path("txnCount"));
                if (n != null) {
                    txns = (txns == null ? 0 : txns) + n;
                }
                // Several files per account: the worst verdict is the one staff must see.
                authenticity = worse(authenticity, text(f.path("authenticity")));
            }
            accounts.add(new BsaAccount(
                    text(a.path("accountNumber")),
                    text(a.path("accountTypeName")),
                    text(a.path("bankName")),
                    text(a.path("status")),
                    text(a.path("ifscCode")),
                    text(a.path("micrCode")),
                    text(a.path("branchName")),
                    text(a.path("accountHolderName")),
                    text(a.path("startDate")),
                    text(a.path("endDate")),
                    txns,
                    authenticity));
        }
        return accounts;
    }

    /** ORIGINAL &lt; UNKNOWN &lt; REVIEW &lt; FRAUD SUSPECTED — the later one wins. */
    static String worse(String a, String b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(String authenticity) {
        if (authenticity == null || authenticity.isBlank()) {
            return -1;
        }
        return switch (authenticity.trim().toUpperCase()) {
            case "ORIGINAL" -> 0;
            case "UNKNOWN" -> 1;
            case "REVIEW" -> 2;
            case "FRAUD SUSPECTED" -> 3;
            default -> 1;
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
