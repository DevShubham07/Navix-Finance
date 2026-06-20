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

    // TODO: implement query — one open case per loan.
    Optional<CollectionCase> findByLoanId(UUID loanId);

    // TODO: implement query — cases on an officer's worklist.
    List<CollectionCase> findByAssignedOfficerId(UUID assignedOfficerId);
}
