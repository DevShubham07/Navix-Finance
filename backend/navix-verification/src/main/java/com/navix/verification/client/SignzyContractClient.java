package com.navix.verification.client;

import static com.navix.verification.support.ProviderJson.bool;
import static com.navix.verification.support.ProviderJson.integer;
import static com.navix.verification.support.ProviderJson.post;
import static com.navix.verification.support.ProviderJson.postTolerating;
import static com.navix.verification.support.ProviderJson.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.dto.SignzyDtos.ContractInitiateRequest;
import com.navix.verification.dto.SignzyDtos.ContractInitiateResponse;
import com.navix.verification.dto.SignzyDtos.ContractPullRequest;
import com.navix.verification.dto.SignzyDtos.ContractPullResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signzy Contract eSign — Aadhaar eSign (eMudhra) of the sanction letter, a two-step redirect flow:
 * {@code POST /api/v3/contract/initiate} mints a contract and a per-signer hosted {@code esignUrl}, and
 * {@code POST /api/v3/contract/pullData} resolves it by contract id.
 *
 * <p>Runs on the PRODUCTION account ({@link VerificationClientConfig#SIGNZY_PROD_CLIENT}): the
 * preproduction account answers {@code 403 "You cannot consume this service"} because the contract
 * product is not entitled there. Unlike the rest of the Signzy surface these endpoints do not require
 * the {@code x-client-unique-id} header, but the shared client sends it harmlessly.
 *
 * <p>Both responses are <em>flat</em> — no {@code result} wrapper.
 */
@Component
public class SignzyContractClient {

    private static final String INITIATE = "/api/v3/contract/initiate";
    private static final String PULL_DATA = "/api/v3/contract/pullData";

    private final RestClient signzy;

    public SignzyContractClient(@Qualifier(VerificationClientConfig.SIGNZY_PROD_CLIENT) RestClient signzy) {
        this.signzy = signzy;
    }

    /**
     * Step 1 — create the contract. Every call mints a real, billable, legally binding Aadhaar eSign
     * contract, so callers must not retry speculatively.
     */
    public ContractInitiateResponse initiate(ContractInitiateRequest request) {
        JsonNode root = post(signzy, INITIATE, request);
        // One signer (the borrower); the signer id and hosted URL live on the first element.
        JsonNode signer = root.path("signerdetail").path(0);
        return new ContractInitiateResponse(
                text(root.path("contractId")),
                text(signer.path("signerId")),
                text(signer.path("esignUrl")),
                text(root.path("initialContractHash")));
    }

    /**
     * Step 2 — resolve the contract. Signzy 404s ("Contract Not Found") once a contract has lapsed or
     * if the id is unknown; that is a normal terminal state for us (the sanction outlives the contract),
     * so it maps to {@link ContractPullResponse#notFound} rather than an error.
     */
    public ContractPullResponse pullData(String contractId) {
        JsonNode root = postTolerating(signzy, PULL_DATA, new ContractPullRequest(contractId), 404);
        if (root == null) {
            return ContractPullResponse.notFound(contractId);
        }
        JsonNode signer = root.path("signerdetail").path(0);
        return new ContractPullResponse(
                text(root.path("contractId")),
                true,
                Boolean.TRUE.equals(bool(root.path("isCompleted"))),
                text(root.path("contractStatus")),
                integer(root.path("signedSignerCount")),
                text(root.path("finalSignedContract")),
                text(root.path("finalSignedContractHash")),
                text(root.path("auditCertificateUrl")),
                text(root.path("contractCompletionTime")),
                text(signer.path("status")),
                text(signer.path("errorMessage")),
                // The published docs call this matchScoreResult and type it as an object; the live API
                // returns nameMatchResult as a string.
                text(signer.path("nameMatchResult")));
    }
}
