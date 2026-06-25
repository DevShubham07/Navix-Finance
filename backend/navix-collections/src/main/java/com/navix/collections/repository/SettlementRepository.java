package com.navix.collections.repository;

import com.navix.collections.entity.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Persistence for {@link Settlement}. */
@Repository
public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    /** Settlements proposed on a case, for the case worklist. */
    List<Settlement> findByCollectionCaseId(UUID collectionCaseId);
}
