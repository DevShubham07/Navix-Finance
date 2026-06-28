package com.navix.verification.service;

import com.navix.common.verification.BureauReportFacts;
import com.navix.verification.client.CrifClient;
import com.navix.verification.client.ExperianClient;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
import com.navix.verification.dto.FintrixDtos.ExperianResponse;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the bureau pull: Experian PRIMARY, CRIF FALLBACK, and maps either
 * source into ONE unified risk object consumed by the income-risk module.
 */
@Service
public class BureauService {

    private final ExperianClient experianClient;
    private final CrifClient crifClient;

    public BureauService(ExperianClient experianClient, CrifClient crifClient) {
        this.experianClient = experianClient;
        this.crifClient = crifClient;
    }

    /**
     * Unified, bureau-agnostic credit summary. {@code source} records which bureau answered
     * (EXPERIAN / CRIF); {@code noRecord} flags a thin-file (no usable score).
     */
    public record UnifiedBureauReport(
            String txnId,
            String source,
            boolean noRecord,
            Integer score,
            Integer activeAccounts,
            Integer overdueAccounts,
            Double totalBalance,
            Integer enquiriesLast6m,
            BureauReportFacts facts
    ) {
    }

    /**
     * Pull Experian first; on error, missing score, or a no-record (thin-file) result, fall back to
     * CRIF, then normalise either response into {@link UnifiedBureauReport}.
     */
    public UnifiedBureauReport pull(String pan, String name, String mobile, String dob, String clientRef) {
        try {
            ExperianResponse experian = experianClient.pull(pan, name, mobile, clientRef);
            if (experian != null
                    && experian.creditScore() != null
                    && !Boolean.TRUE.equals(experian.noRecord())) {
                return fromExperian(experian);
            }
        } catch (RuntimeException primaryFailure) {
            // PRIMARY bureau miss/error — fall through to the CRIF fallback below.
        }
        return fromCrif(crifClient.pull(pan, name, mobile, dob, clientRef));
    }

    private UnifiedBureauReport fromExperian(ExperianResponse r) {
        // A rich (prod) Experian response carries categorized facts; a sandbox thin-file has facts==null.
        // The account/balance facets below are derived from facts when present.
        BureauReportFacts f = r.facts();
        return new UnifiedBureauReport(r.txnId(), "EXPERIAN", Boolean.TRUE.equals(r.noRecord()),
                r.creditScore(),
                f != null ? f.activeAccounts() : null,
                f != null ? f.defaults() : null,
                f != null && f.totalBalanceRupees() != null ? f.totalBalanceRupees().doubleValue() : null,
                f != null ? f.recentInquiries30d() : null,
                f);
    }

    private UnifiedBureauReport fromCrif(CrifResponse r) {
        // CRIF fallback has a different shape; no categorized brief facts (facts==null).
        return new UnifiedBureauReport(
                r.txnId(),
                "CRIF",
                r.score() == null,
                r.score(),
                r.activeAccounts(),
                r.overdueAccounts(),
                r.totalBalance(),
                r.enquiriesLast6m(),
                null);
    }
}
