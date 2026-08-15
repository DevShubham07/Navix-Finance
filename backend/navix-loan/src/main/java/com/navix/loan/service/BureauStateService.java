package com.navix.loan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.loan.dto.BureauState;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.ApplicationVerificationRepository.BureauStateRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a BUREAU pull ran for an application, and what it found — read off the
 * {@code application_verification} row's {@code status}/{@code derived}, never off application
 * status (pulls happen while the application is still nominally DRAFT). Deliberately separate from
 * {@link CreditBriefService} (which is read-write and drags in the PDF renderer + S3 storage port
 * via {@code ensureBrief}) so list endpoints can depend on just this for a one-field answer.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BureauStateService {

    private static final String BUREAU = "BUREAU";
    private static final int CHUNK_SIZE = 1000;

    private final ApplicationVerificationRepository verificationRepo;
    private final ObjectMapper objectMapper;

    /** Single-application lookup. */
    public BureauState state(Long applicationId) {
        return verificationRepo.findByApplicationIdAndCheckType(applicationId, BUREAU)
                .map(row -> classify(row.getStatus(), row.getDerived()))
                .orElse(BureauState.NOT_FETCHED);
    }

    /**
     * Batched lookup for list views. Ids with no BUREAU row are simply absent from the returned
     * map — callers must default via {@code states.getOrDefault(id, BureauState.NOT_FETCHED)}.
     * Chunks the id list to stay clear of JDBC/Postgres {@code IN (...)} parameter limits on large
     * registers.
     */
    public Map<Long, BureauState> states(Collection<Long> applicationIds) {
        if (applicationIds == null || applicationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BureauState> out = new HashMap<>();
        List<Long> ids = new ArrayList<>(applicationIds);
        for (int from = 0; from < ids.size(); from += CHUNK_SIZE) {
            List<Long> chunk = ids.subList(from, Math.min(from + CHUNK_SIZE, ids.size()));
            for (BureauStateRow row : verificationRepo.findByCheckTypeAndApplicationIdIn(BUREAU, chunk)) {
                out.put(row.getApplicationId(), classify(row.getStatus(), row.getDerived()));
            }
        }
        return out;
    }

    private BureauState classify(String status, String derived) {
        if (!"PASS".equals(status)) {
            return BureauState.NOT_FETCHED;
        }
        // Cheap short-circuit before parsing — avoids a JSON parse per row for the common case.
        if (derived == null || !derived.contains("noRecord")) {
            return BureauState.FOUND;
        }
        try {
            return objectMapper.readTree(derived).path("noRecord").asBoolean(false)
                    ? BureauState.NO_RECORD : BureauState.FOUND;
        } catch (Exception e) {
            // A stored-JSON problem means the pull DID complete — degrade to FOUND (rich-but-empty
            // display), never silently claim "not fetched".
            log.warn("Could not parse stored derived JSON for a BUREAU verification row: {}", e.toString());
            return BureauState.FOUND;
        }
    }
}
