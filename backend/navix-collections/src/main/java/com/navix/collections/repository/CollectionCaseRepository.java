package com.navix.collections.repository;

import com.navix.collections.entity.CollectionCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for {@link CollectionCase}. */
@Repository
public interface CollectionCaseRepository extends JpaRepository<CollectionCase, UUID> {

    /** One open case per real loan (bigint loan id). */
    Optional<CollectionCase> findByLoanId(Long loanId);

    /** Cases on an officer's worklist (bigint staff id). */
    List<CollectionCase> findByAssignedOfficerId(Long assignedOfficerId);
}
