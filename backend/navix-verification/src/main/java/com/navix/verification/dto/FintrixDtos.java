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

    public record CrifResponse(
            String txnId,
            Integer score,
            boolean noRecord,
            BureauReportFacts facts,
            String rawResponseJson,
            String creditReportLink
    ) {
    }
}
