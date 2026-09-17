package com.navix.collections.repository;

import com.navix.collections.entity.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Persistence for {@link Settlement}. */
@Repository
public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    /** Settlements proposed on a case, for the case worklist. */
    List<Settlement> findByCollectionCaseId(UUID collectionCaseId);

    /**
     * Settlements on a whole set of cases, in one query — backs the batched
     * {@code SettlementDirectory#approvedSettlementAmounts}. Callers MUST short-circuit on an empty
     * collection; {@code in ()} is not valid SQL.
     */
    List<Settlement> findByCollectionCaseIdIn(Collection<UUID> collectionCaseIds);
}
