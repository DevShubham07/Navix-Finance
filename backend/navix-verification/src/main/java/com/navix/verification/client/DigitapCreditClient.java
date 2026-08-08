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
 * timestamp} must be the literal {@code ddMMyyyy-HH:mm:ss} format, not epoch millis, and {@code
 * consent_message} must contain the words I/authorize/Experian/credit/report or Digitap rejects it
 * with {@code "please provide proper consent message"}.
 */
@Component
public class DigitapCreditClient {

    private static final String ENDPOINT = "/credit_analytics/request";
    private static final String CONSENT_MESSAGE =
            "I hereby authorize Experian to pull my credit report for loan evaluation.";
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("ddMMyyyy-HH:mm:ss");

    private final RestClient digitapApi;
    private final String deviceIp;

    public DigitapCreditClient(@Qualifier(VerificationClientConfig.DIGITAP_API_CLIENT) RestClient digitapApi,
                               @Value("${navix.digitap.device-ip:3.109.169.131}") String deviceIp) {
        this.digitapApi = digitapApi;
        this.deviceIp = deviceIp;
    }

    public CreditResponse pull(String pan, String name, String mobile, String dob, String otp, String clientRef) {
        String[] parts = splitName(name);
        CreditRequest req = new CreditRequest(
                ref(clientRef), mobile, 0, parts[0], parts[1], pan, dob,
                CONSENT_MESSAGE, "Yes", "server", otp == null ? "" : otp,
                TIMESTAMP_FORMAT.format(LocalDateTime.now()), deviceIp, "navix");
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
