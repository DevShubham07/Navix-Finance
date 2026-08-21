package com.navix.collections.service;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.common.collections.CollectionCaseDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collections-module implementation of the {@link CollectionCaseDirectory} port: resolves a batch
 * of real loan ids to their assigned collections officer in one query. Mirrors {@link
 * SettlementDirectoryAdapter} (the same "loan → collection_case" seam, for the approved-settlement
 * figure instead) — wired by component scan, consumed by the loan module's staff loan register so
 * the "Officer" column can be filled without navix-loan depending on navix-collections.
 */
@Component
@RequiredArgsConstructor
public class CollectionCaseDirectoryAdapter implements CollectionCaseDirectory {

    private final CollectionCaseRepository caseRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> assignedOfficerByLoanId(Collection<Long> loanIds) {
        if (loanIds == null || loanIds.isEmpty()) {
            return Map.of();
        }
        List<CollectionCase> cases = caseRepository.findByLoanIdIn(loanIds);

        // A loan is not guaranteed to have exactly one case (see CollectionCaseRepository's
        // findFirstByLoanIdOrderByCreatedAtDesc javadoc) — group by loan id and keep the most
        // recently opened one, same tie-break as that method, then drop any without an assignee
        // yet rather than surfacing a null officer id in the map.
        Map<Long, CollectionCase> latestByLoanId = new HashMap<>();
        for (CollectionCase c : cases) {
            latestByLoanId.merge(c.getLoanId(), c,
                    (existing, candidate) -> candidate.getCreatedAt() != null
                            && (existing.getCreatedAt() == null
                                    || candidate.getCreatedAt().isAfter(existing.getCreatedAt()))
                            ? candidate
                            : existing);
        }

        Map<Long, Long> result = new HashMap<>();
        for (CollectionCase c : latestByLoanId.values()) {
            if (c.getAssignedOfficerId() != null) {
                result.put(c.getLoanId(), c.getAssignedOfficerId());
            }
        }
        return result;
    }
}
