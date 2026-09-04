package com.navix.app.provider;

import com.navix.common.verification.ProviderAttemptDirectory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplies {@link ProviderAttemptDirectory} from {@code provider_api_execution}, the table this
 * module owns. See the interface for why the seam exists.
 *
 * <p>Reads a projection that deliberately omits {@code request_json}/{@code response_json} — one
 * bureau row carries a few hundred KB of credit report, and none of it is needed to say which
 * providers were tried.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ProviderApiAttemptDirectory implements ProviderAttemptDirectory {

    /** {@code ProviderCall.FAILED} — the audit table's only two statuses are SUCCESS and FAILED. */
    private static final String FAILED = "FAILED";

    private final ProviderApiExecutionRepository repository;

    @Override
    public Map<Long, List<Attempt>> byApplicationId(Collection<Long> applicationIds) {
        if (applicationIds == null || applicationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Attempt>> out = new HashMap<>();
        for (ProviderApiExecutionRepository.AttemptRow row
                : repository.findAttempts(applicationIds)) {
            out.computeIfAbsent(row.getApplicationId(), k -> new ArrayList<>())
                    .add(new Attempt(row.getProvider(), row.getOperation(), row.getHttpStatus(),
                            !FAILED.equals(row.getStatus()),
                            row.getCreatedAt()));
        }
        return out;
    }
}
