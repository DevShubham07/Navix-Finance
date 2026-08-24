package com.navix.verification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.navix.common.verification.BureauReportFacts;

/**
 * Request/response records for Fintrix {@code POST /crif_combine} — the PRIMARY bureau pull (CRIF
 * Highmark). Fintrix's envelope is {@code {success, canonical:{timestamp, transaction_id, status,
 * data:{name, mobile, credit_report, credit_report_link}}, is_sandbox, request_id, transaction_id}}.
 * Only {@code name}/{@code mobile} are sent — Fintrix has no PAN input on this endpoint; it decides who
 * you meant and hands back a PAN/DOB in the report for the caller to cross-check.
 */
public final class FintrixDtos {

    private FintrixDtos() {
    }

    public record CrifRequest(
            @JsonProperty("name") String name,
            @JsonProperty("mobile") String mobile,
            @JsonProperty("remark") String remark,
            @JsonProperty("consent") String consent) {
    }

    /**
     * {@code challenge} is populated instead of everything else when CRIF answers with a
     * knowledge-based-authentication (KBA) question — see {@link Challenge}. It is deliberately NOT
     * {@code noRecord}: the report exists (CRIF hands back a {@code report_id}), it's just gated behind
     * a question only the borrower can answer.
     */
    public record CrifResponse(
            String txnId,
            Integer score,
            boolean noRecord,
            BureauReportFacts facts,
            String rawResponseJson,
            String creditReportLink,
            Challenge challenge
    ) {
        /** Back-compat constructor for the no-challenge path (every call site but the KBA branch). */
        public CrifResponse(String txnId, Integer score, boolean noRecord, BureauReportFacts facts,
                            String rawResponseJson, String creditReportLink) {
            this(txnId, score, noRecord, facts, rawResponseJson, creditReportLink, null);
        }
    }

    /**
     * A pending CRIF knowledge-based-authentication challenge. {@code orderId} and {@code reportId}
     * are BOTH required to answer it via {@code /bureau_ch_user_auth}, so both must be persisted when
     * the challenge is parked — dropping {@code reportId} is what left the first production cohort
     * unanswerable without a fresh (billable) pull.
     */
    public record Challenge(String question, java.util.List<String> options, String orderId,
                           String reportId) {
    }

    /**
     * Request for {@code POST /bureau_ch_user_auth} — answers a pending KBA challenge and releases the
     * report it was gating. Verified live 2026-08-24.
     *
     * <p><b>{@code authAnswers} is the chosen option VERBATIM</b>, including its leading/trailing
     * spaces (CRIF returns options space-padded, e.g. {@code " PAYU FINANCE INDIA PRIVATE LIMITED "},
     * and compares literally). Do not trim it here or anywhere upstream.
     *
     * <p>Note this endpoint authenticates with {@code X-Client-ID}/{@code X-Client-Secret} headers
     * rather than the {@code Authorization: Basic} that {@code /crif_combine} uses — see
     * {@code VerificationClientConfig}.
     */
    public record CrifAuthAnswerRequest(
            @JsonProperty("remark") String remark,
            @JsonProperty("order_id") String orderId,
            @JsonProperty("report_id") String reportId,
            @JsonProperty("auth_answers") String authAnswers) {
    }
}
