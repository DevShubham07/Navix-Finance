package com.navix.collections.repository;

import com.navix.collections.entity.CollectionCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for {@link CollectionCase}. */
@Repository
public interface CollectionCaseRepository extends JpaRepository<CollectionCase, UUID> {

    /** One open case per real loan (bigint loan id). */
    Optional<CollectionCase> findByLoanId(Long loanId);

    /**
     * Every case whose {@code loan_id} is in {@code loanIds}, in one query — backs {@code
     * CollectionCaseDirectoryAdapter} so the staff loan register can resolve a whole page of loans'
     * assigned officers without a per-loan round trip. Callers MUST short-circuit on an empty
     * collection; {@code in ()} is not valid SQL.
     */
    List<CollectionCase> findByLoanIdIn(Collection<Long> loanIds);

    /**
     * The most recently opened case for a real loan (bigint loan id). {@code openCase} is
     * idempotent per loan under normal operation (it looks up {@link #findByLoanId} before
     * inserting), so duplicates should not occur — but there is no DB-level unique constraint
     * on {@code loan_id}, so a concurrent double-open is not structurally impossible. Ordering
     * by {@code createdAt} desc means a lookup by loan id always resolves deterministically to
     * the newest case rather than throwing on a non-unique result.
     */
    Optional<CollectionCase> findFirstByLoanIdOrderByCreatedAtDesc(Long loanId);

    /** Cases on an officer's worklist (bigint staff id). */
    List<CollectionCase> findByAssignedOfficerId(Long assignedOfficerId);
}
