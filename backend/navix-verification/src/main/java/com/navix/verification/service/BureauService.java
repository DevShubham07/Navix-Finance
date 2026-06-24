package com.navix.verification.service;

import com.navix.verification.client.CrifClient;
import com.navix.verification.client.ExperianClient;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
import com.navix.verification.dto.FintrixDtos.ExperianResponse;
import com.navix.verification.dto.FintrixDtos.Tradeline;
import java.util.List;
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
     *
     * <p>The orchestration is real (primary/fallback selection + unification); the underlying
     * clients return demo mocks pending live Fintrix credentials.
     */
    public UnifiedBureauReport pull(String pan, String name, String mobile) {
        try {
            ExperianResponse experian = experianClient.pull(pan, name, mobile);
            if (experian != null && experian.creditScore() != null) {
                return fromExperian(experian);
            }
        } catch (RuntimeException primaryFailure) {
            // PRIMARY bureau miss/error — fall through to the CRIF fallback below.
        }
        return fromCrif(crifClient.pull(pan, name, mobile));
    }

    private UnifiedBureauReport fromExperian(ExperianResponse r) {
        List<Tradeline> tradelines = r.tradelines() != null ? r.tradelines() : List.of();
        int active = (int) tradelines.stream()
                .filter(t -> "ACTIVE".equalsIgnoreCase(t.status()))
                .count();
        int overdue = (int) tradelines.stream()
                .filter(t -> t.overdueAmount() != null && t.overdueAmount() > 0)
                .count();
        double totalBalance = tradelines.stream()
                .mapToDouble(t -> t.balance() != null ? t.balance() : 0.0)
                .sum();
        return new UnifiedBureauReport(r.creditScore(), active, overdue, totalBalance, null);
    }

    private UnifiedBureauReport fromCrif(CrifResponse r) {
        var summary = r.accountsSummary();
        if (summary == null) {
            return new UnifiedBureauReport(r.score(), null, null, null, null);
        }
        return new UnifiedBureauReport(
                r.score(),
                summary.activeAccounts(),
                summary.overdueAccounts(),
                summary.totalBalance(),
                summary.enquiriesLast6m());
    }
}
