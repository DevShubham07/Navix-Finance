package com.navix.collections.service;

import com.navix.collections.entity.Settlement;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.collections.SettlementDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Optional;

/**
 * Collections-module implementation of the {@link SettlementDirectory} port: resolves a real loan id
 * to the operative <b>approved</b> settlement amount on its collection case. Mirrors
 * {@code LoanDirectoryAdapter} (the reverse seam) — wired by component scan, consumed by the loan
 * module's {@code RepaymentService} so an approved settlement caps the borrower's outstanding.
 */
@Component
@RequiredArgsConstructor
public class SettlementDirectoryAdapter implements SettlementDirectory {

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
                        .filter(s -> s.getApprovedBy() != null)
                        // The most recently approved settlement is the operative full-and-final figure.
                        .max(Comparator.comparing(Settlement::getApprovedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                        .map(Settlement::getSettlementAmount));
    }
}
