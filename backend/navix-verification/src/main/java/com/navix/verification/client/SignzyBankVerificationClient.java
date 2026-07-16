package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.SignzyDtos.BankVerificationRequest;
import com.navix.verification.dto.SignzyDtos.BankVerificationResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signzy penny-drop bank account verification — {@code POST /api/v3/bankaccountverification/pennydrop-v1}
 * on the Signzy PRODUCTION account. The direct Signzy equivalent of NAVIX's penny-drop payout name-match
 * gate. Response is wrapped in {@code result:{...}} (active, nameMatch, signzyReferenceId, bankTransfer).
 */
@Component
public class SignzyBankVerificationClient {

    private static final String ENDPOINT = "/api/v3/bankaccountverification/pennydrop-v1";

    private final RestClient signzy;

    public SignzyBankVerificationClient(@Qualifier(VerificationClientConfig.SIGNZY_PROD_CLIENT) RestClient signzy) {
        this.signzy = signzy;
    }

    public BankVerificationResponse verify(String accountNumber, String ifsc, String beneficiaryName) {
        JsonNode root = post(signzy, ENDPOINT,
                new BankVerificationRequest(accountNumber, ifsc, beneficiaryName, "true"));
        JsonNode result = root.path("result");
        JsonNode transfer = result.path("bankTransfer");
        return new BankVerificationResponse(
                text(result.path("signzyReferenceId")),
                bool(result.path("active")),
                text(result.path("reason")),
                text(result.path("nameMatch")),
                text(transfer.path("beneName")),
                text(transfer.path("beneIFSC")),
                text(result.path("auditTrail").path("value")));
    }
}
