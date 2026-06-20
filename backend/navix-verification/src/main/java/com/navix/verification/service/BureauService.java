package com.navix.verification.service;

import com.navix.verification.client.CrifClient;
import com.navix.verification.client.ExperianClient;
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
     * Unified, bureau-agnostic credit summary.
     */
    public record UnifiedBureauReport(
            Integer score,
            Integer activeAccounts,
            Integer overdueAccounts,
            Double totalBalance,
            Integer enquiriesLast6m
    ) {
    }

    /**
     * Pull Experian first; on miss/error fall back to CRIF, then normalise either
     * response into {@link UnifiedBureauReport}.
     * TODO: implement primary/fallback selection + mapping from Experian/CRIF DTOs.
     */
    public UnifiedBureauReport pull(String pan, String name, String mobile) {
        throw new UnsupportedOperationException("TODO: orchestrate Experian -> CRIF and unify");
    }
}
