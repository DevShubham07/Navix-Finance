package com.navix.verification.service;

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
            Integer enquiriesLast6m
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
        // Sandbox Experian returns the score only (CAIS account detail is empty), so the
        // account/balance facets are left null here.
        return new UnifiedBureauReport(r.txnId(), "EXPERIAN", Boolean.TRUE.equals(r.noRecord()),
                r.creditScore(), null, null, null, null);
    }

    private UnifiedBureauReport fromCrif(CrifResponse r) {
        return new UnifiedBureauReport(
                r.txnId(),
                "CRIF",
                r.score() == null,
                r.score(),
                r.activeAccounts(),
                r.overdueAccounts(),
                r.totalBalance(),
                r.enquiriesLast6m());
    }
}
