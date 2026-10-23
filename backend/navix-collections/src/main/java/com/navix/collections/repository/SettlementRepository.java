package com.navix.collections.repository;

import com.navix.collections.entity.Settlement;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    /**
     * Settlements proposed, approved or rejected by any of {@code staffIds} in {@code [from, to)},
     * in one read for the staff-performance dashboard. Callers MUST short-circuit on an empty
     * collection.
     */
    @Query("""
            select s from Settlement s
            where (s.proposedBy in :staffIds and s.createdAt >= :from and s.createdAt < :to)
               or (s.approvedBy in :staffIds and s.approvedAt >= :from and s.approvedAt < :to)
               or (s.rejectedBy in :staffIds and s.rejectedAt >= :from and s.rejectedAt < :to)
            """)
    List<Settlement> findTouchedByStaffInWindow(@Param("staffIds") Collection<Long> staffIds,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);
}
