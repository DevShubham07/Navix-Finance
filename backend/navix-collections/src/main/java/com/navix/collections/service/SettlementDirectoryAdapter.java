package com.navix.collections.service;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.Settlement;
import com.navix.collections.entity.SettlementStatus;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.collections.SettlementDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Collections-module implementation of the {@link SettlementDirectory} port: resolves a real loan id
 * to the operative <b>approved</b> settlement amount on its collection case. Mirrors
 * {@code LoanDirectoryAdapter} (the reverse seam) — wired by component scan, consumed by the loan
 * module's {@code RepaymentService} so an approved settlement caps the borrower's outstanding.
 */
@Component
@RequiredArgsConstructor
public class SettlementDirectoryAdapter implements SettlementDirectory {

    /** The single-loan "latest approved wins" rule, hoisted so the batched path orders identically. */
    private static final Comparator<Settlement> BY_APPROVED_AT =
            Comparator.comparing(Settlement::getApprovedAt, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static final Comparator<CollectionCase> BY_CREATED_AT =
            Comparator.comparing(CollectionCase::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final CollectionCaseRepository caseRepository;
    private final SettlementRepository settlementRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> approvedSettlementAmount(Long loanId) {
        if (loanId == null) {
            return Optional.empty();
        }
        return caseRepository.findByLoanId(loanId)
                .flatMap(c -> settlementRepository.findByCollectionCaseId(c.getId()).stream()
                        .filter(s -> s.getStatus() == SettlementStatus.APPROVED)
                        // The most recently approved settlement is the operative full-and-final figure.
                        .max(BY_APPROVED_AT)
                        .map(Settlement::getSettlementAmount));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> approvedSettlementAmounts(Collection<Long> loanIds) {
        if (loanIds == null || loanIds.isEmpty()) {
            return Map.of();
        }
        // One case per loan under normal operation, but loan_id carries no unique constraint, so a
        // concurrent double-open is not structurally impossible. The single-loan findByLoanId returns
        // an Optional and would THROW on a duplicate — i.e. this is the one deliberate divergence
        // from approvedSettlementAmount, and only in a state that path cannot serve at all. Resolve
        // it the way findFirstByLoanIdOrderByCreatedAtDesc does: newest case wins.
        Map<Long, CollectionCase> newestCaseByLoanId = new HashMap<>();
        for (CollectionCase c : caseRepository.findByLoanIdIn(loanIds)) {
            newestCaseByLoanId.merge(c.getLoanId(), c,
                    (kept, candidate) -> BY_CREATED_AT.compare(kept, candidate) >= 0 ? kept : candidate);
        }
        if (newestCaseByLoanId.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> loanIdByCaseId = new HashMap<>();
        newestCaseByLoanId.forEach((loanId, c) -> loanIdByCaseId.put(c.getId(), loanId));

        List<Settlement> settlements = settlementRepository.findByCollectionCaseIdIn(loanIdByCaseId.keySet());
        Map<Long, Settlement> operativeByLoanId = new HashMap<>();
        for (Settlement s : settlements) {
            if (s.getStatus() != SettlementStatus.APPROVED) {
                continue;
            }
            Long loanId = loanIdByCaseId.get(s.getCollectionCaseId());
            if (loanId == null) {
                continue; // a settlement on a case we did not ask for (defensive)
            }
            // The most recently approved settlement is the operative full-and-final figure.
            operativeByLoanId.merge(loanId, s,
                    (kept, candidate) -> BY_APPROVED_AT.compare(kept, candidate) >= 0 ? kept : candidate);
        }
        Map<Long, Long> result = new HashMap<>();
        // Absent, never zero: a loan with no approved settlement must read as "no concession".
        operativeByLoanId.forEach((loanId, s) -> {
            if (s.getSettlementAmount() != null) {
                result.put(loanId, s.getSettlementAmount());
            }
        });
        return result;
    }
}
