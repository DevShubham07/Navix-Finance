package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.ref;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.common.verification.BureauReportFacts;
import com.navix.common.verification.ProviderFailureDetails;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.DigitapDtos.CreditRequest;
import com.navix.verification.dto.DigitapDtos.CreditResponse;
import com.navix.verification.exception.VerificationException;
import com.navix.verification.support.ExperianFactsParser;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Digitap Credit Analytics — {@code POST /credit_analytics/request} (api host). The FALLBACK bureau
 * (Experian). The report body is Experian's {@code INProfileResponse} at
 * {@code result.result_json.INProfileResponse} — the same shape {@link ExperianFactsParser} reads — with
 * the score at {@code SCORE.BureauScore}.
 *
 * <p>Digitap's live Credit Analytics requires OTP-based consent: {@code otp} must be the code the
 * borrower actually verified (threaded in by the caller — see {@code VerificationPort.pullBureau}), and
 * {@code device_ip} must match the IP Digitap has allow-listed for this account (a placeholder like
 * {@code 0.0.0.0} is rejected with {@code 403 IP not allowed} — confirmed by live testing). {@code
 * timestamp} must be the literal {@code ddMMyyyy-HH:mm:ss} format, not epoch millis, {@code
 * device_type} must be {@code web} or {@code mobile}, and {@code consent_message} must contain the
 * words I/authorize/Experian/credit/report or Digitap rejects it with
 * {@code "please provide proper consent message"}.
 */
@Component
public class DigitapCreditClient {

    private static final String ENDPOINT = "/credit_analytics/request";
    private static final String CONSENT_MESSAGE =
            "I hereby authorize Experian to pull my credit report for loan evaluation.";
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("ddMMyyyy-HH:mm:ss");

    /**
     * Experian's "records exist, but behind numbers you did not send" code.
     *
     * <p><b>Do not unify this with {@link DigitapCrifClient}.</b> The two Digitap bureau products
     * assign the SAME numbers opposite meanings: on the CRIF product 102 is "no record" and 103 is
     * "name not found", while on this Experian endpoint 103 is the no-record and 102 carries a
     * retrieval instruction. A blanket {@code 102 -> throw} here would turn genuine Experian
     * no-records into failures.
     */
    private static final int RESULT_MASKED_MOBILE = 102;
    private static final String MASKED_MOBILE_MARKER = "masked mobile";

    private final RestClient digitapApi;
    private final String deviceIp;

    public DigitapCreditClient(@Qualifier(VerificationClientConfig.DIGITAP_CREDIT_CLIENT) RestClient digitapApi,
                               @Value("${navix.digitap.device-ip:3.109.169.131}") String deviceIp) {
        this.digitapApi = digitapApi;
        this.deviceIp = deviceIp;
    }

    public CreditResponse pull(String pan, String name, String mobile, String dob, String otp, String clientRef) {
        String[] parts = splitName(name);
        CreditRequest req = new CreditRequest(
                ref(clientRef), mobile, 0, parts[0], parts[1], pan, dob,
                CONSENT_MESSAGE, "Yes", "web", otp == null ? "" : otp,
                TIMESTAMP_FORMAT.format(LocalDateTime.now()), deviceIp);
        JsonNode root = post(digitapApi, ENDPOINT, req);
        JsonNode report = root.path("result").path("result_json").path("INProfileResponse");
        Integer score = integer(report.path("SCORE").path("BureauScore"));
        Integer resultCode = integer(root.path("result_code"));

        // result_code 102 is overloaded. Most of the time the score is simply absent and the row is a
        // legitimate no-record. But Digitap also answers 102 with a message naming the real mobile
        // numbers CRIF holds for this identity and telling us to call a separate report endpoint —
        // records DO exist. Because noRecord short-circuited on the null score, that instruction was
        // never read and 5 applications in the Sep-2026 audit were filed as "no credit history" when
        // their files were one manual step away.
        //
        // Throwing (rather than inventing a new success shape) is deliberate: Digitap is the last leg,
        // so RoutingVerificationPort rethrows this and the bureau step records an honest REVIEW that
        // names what is needed. We do NOT call the masked-mobile endpoint — it is undocumented here.
        String message = text(root.path("message"));
        if (resultCode != null && resultCode == RESULT_MASKED_MOBILE && message != null
                && message.toLowerCase(java.util.Locale.ROOT).contains(MASKED_MOBILE_MARKER)) {
            // The message itself carries real mobile numbers, so it must not travel: safeDetail stays
            // null and the unredacted body lives only in the provider_api_execution audit row.
            throw new VerificationException(
                    "Digitap Experian holds records only under mobile numbers we did not send", null,
                    null, ENDPOINT, ProviderFailureDetails.MASKED_MOBILE_REQUIRED, null);
        }

        boolean noRecord = score == null || (resultCode != null && resultCode == 103);
        BureauReportFacts facts = ExperianFactsParser.parse(report, score, name, pan, mobile);
        String txnId = text(root.path("request_id"));
        return new CreditResponse(txnId, score, noRecord, facts, root.toString());
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
