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
     * A pending CRIF knowledge-based-authentication challenge — {@code order_id} is the handle a future
     * answer-submission flow would need. Answering it needs a Fintrix endpoint we have no docs for
     * (out of scope here); this just carries the question through so a human can see it.
     */
    public record Challenge(String question, java.util.List<String> options, String orderId) {
    }
}
