package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.Settlement;
import com.navix.collections.entity.SettlementStatus;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.SettlementRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The batched {@code approvedSettlementAmounts} exists purely to take the per-loan settlement lookup
 * out of the loan register's hot path, so what matters is that it answers <em>identically</em> to the
 * single-loan read it replaces — same "latest APPROVED wins" rule, same absent-not-zero contract.
 */
@ExtendWith(MockitoExtension.class)
class SettlementDirectoryAdapterTest {

    @Mock
    private CollectionCaseRepository caseRepository;
    @Mock
    private SettlementRepository settlementRepository;

    private SettlementDirectoryAdapter adapter;

    private final UUID caseOne = UUID.randomUUID();
    private final UUID caseTwo = UUID.randomUUID();
    private final UUID caseThree = UUID.randomUUID();

    private static final Instant EARLIER = Instant.now().minus(5, ChronoUnit.DAYS);
    private static final Instant LATER = Instant.now().minus(1, ChronoUnit.DAYS);

    @BeforeEach
    void setUp() {
        adapter = new SettlementDirectoryAdapter(caseRepository, settlementRepository);
    }

    private CollectionCase collectionCase(UUID id, long loanId) {
        CollectionCase c = new CollectionCase();
        c.setId(id);
        c.setLoanId(loanId);
        c.setCreatedAt(EARLIER);
        return c;
    }

    private Settlement settlement(UUID caseId, long amount, SettlementStatus status, Instant approvedAt) {
        Settlement s = new Settlement();
        s.setId(UUID.randomUUID());
        s.setCollectionCaseId(caseId);
        s.setSettlementAmount(amount);
        s.setStatus(status);
        s.setProposedBy(9L);
        s.setApprovedAt(approvedAt);
        return s;
    }

    @Test
    void returnsLatestApprovedPerLoan() {
        // Loan 1: two approvals plus a live proposal — the most recent approval is the operative one,
        // and the still-PROPOSED (higher) figure must not leak in as a concession nobody granted.
        Settlement supersededOnOne = settlement(caseOne, 800_000L, SettlementStatus.APPROVED, EARLIER);
        Settlement operativeOnOne = settlement(caseOne, 500_000L, SettlementStatus.APPROVED, LATER);
        Settlement proposedOnOne = settlement(caseOne, 100_000L, SettlementStatus.PROPOSED, null);
        // Loan 2: a null approvedAt must lose to a real timestamp (nullsFirst), not win by accident.
        Settlement undatedOnTwo = settlement(caseTwo, 900_000L, SettlementStatus.APPROVED, null);
        Settlement datedOnTwo = settlement(caseTwo, 700_000L, SettlementStatus.APPROVED, EARLIER);

        when(caseRepository.findByLoanIdIn(List.of(1L, 2L)))
                .thenReturn(List.of(collectionCase(caseOne, 1L), collectionCase(caseTwo, 2L)));
        when(settlementRepository.findByCollectionCaseIdIn(anyCollection())).thenReturn(
                List.of(supersededOnOne, operativeOnOne, proposedOnOne, undatedOnTwo, datedOnTwo));

        assertThat(adapter.approvedSettlementAmounts(List.of(1L, 2L)))
                .containsOnly(Map.entry(1L, 500_000L), Map.entry(2L, 700_000L));
    }

    @Test
    void omitsLoansWithoutCaseOrWithoutApprovedSettlement() {
        // Loan 3 has a case but nothing approved on it; loan 4 has no case at all. Neither may appear —
        // a present 0 would read as "settled at nothing" and close the loan for free.
        when(caseRepository.findByLoanIdIn(List.of(1L, 3L, 4L)))
                .thenReturn(List.of(collectionCase(caseOne, 1L), collectionCase(caseThree, 3L)));
        when(settlementRepository.findByCollectionCaseIdIn(anyCollection())).thenReturn(List.of(
                settlement(caseOne, 500_000L, SettlementStatus.APPROVED, LATER),
                settlement(caseThree, 400_000L, SettlementStatus.PROPOSED, null),
                settlement(caseThree, 300_000L, SettlementStatus.REJECTED, null)));

        Map<Long, Long> amounts = adapter.approvedSettlementAmounts(List.of(1L, 3L, 4L));

        assertThat(amounts).containsOnlyKeys(1L);
        assertThat(amounts.get(3L)).isNull();
        assertThat(amounts.get(4L)).isNull();
    }

    @Test
    void agreesWithTheSingleLoanLookup() {
        List<Settlement> onCaseOne = List.of(
                settlement(caseOne, 800_000L, SettlementStatus.APPROVED, EARLIER),
                settlement(caseOne, 500_000L, SettlementStatus.APPROVED, LATER),
                settlement(caseOne, 100_000L, SettlementStatus.PROPOSED, null));
        List<Settlement> onCaseThree = List.of(
                settlement(caseThree, 400_000L, SettlementStatus.PROPOSED, null));

        // Same fixtures, reached through each method's own repository calls.
        when(caseRepository.findByLoanId(1L)).thenReturn(Optional.of(collectionCase(caseOne, 1L)));
        when(caseRepository.findByLoanId(3L)).thenReturn(Optional.of(collectionCase(caseThree, 3L)));
        when(settlementRepository.findByCollectionCaseId(caseOne)).thenReturn(onCaseOne);
        when(settlementRepository.findByCollectionCaseId(caseThree)).thenReturn(onCaseThree);
        when(caseRepository.findByLoanIdIn(List.of(1L, 3L)))
                .thenReturn(List.of(collectionCase(caseOne, 1L), collectionCase(caseThree, 3L)));
        when(settlementRepository.findByCollectionCaseIdIn(anyCollection()))
                .thenReturn(java.util.stream.Stream.concat(onCaseOne.stream(), onCaseThree.stream()).toList());

        Map<Long, Long> batched = adapter.approvedSettlementAmounts(List.of(1L, 3L));

        assertThat(Optional.ofNullable(batched.get(1L))).isEqualTo(adapter.approvedSettlementAmount(1L));
        assertThat(Optional.ofNullable(batched.get(3L))).isEqualTo(adapter.approvedSettlementAmount(3L));
    }

    @Test
    void emptyInputIssuesNoQueries() {
        // `in ()` is not valid SQL, and a register page with no loans must cost nothing at all.
        assertThat(adapter.approvedSettlementAmounts(List.of())).isEmpty();
        assertThat(adapter.approvedSettlementAmounts(null)).isEmpty();

        verifyNoInteractions(caseRepository, settlementRepository);
    }
}
