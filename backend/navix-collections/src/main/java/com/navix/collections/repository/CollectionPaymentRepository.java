package com.navix.collections.repository;

import com.navix.collections.entity.CollectionPayment;
import com.navix.collections.entity.CollectionPaymentStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for collections-side payments and their two-step approval (V47). */
@Repository
public interface CollectionPaymentRepository extends JpaRepository<CollectionPayment, UUID> {

    List<CollectionPayment> findByCollectionCaseIdOrderByRaisedAtDesc(UUID collectionCaseId);

    List<CollectionPayment> findByLoanIdOrderByRaisedAtDesc(Long loanId);

    /** The Accountant's queue, and the Collection Head's, depending on the status asked for. */
    List<CollectionPayment> findByStatusOrderByRaisedAtAsc(CollectionPaymentStatus status);

    List<CollectionPayment> findAllByOrderByRaisedAtDesc();

    /**
     * Payments touched by any of {@code staffIds} in {@code [from, to)} — recorded by them, or
     * validated/rejected by them — in one read for the staff-performance dashboard. Callers MUST
     * short-circuit on an empty collection.
     */
    @Query("""
            select p from CollectionPayment p
            where (p.raisedBy in :staffIds and p.raisedAt >= :from and p.raisedAt < :to)
               or (p.validatedBy in :staffIds and p.validatedAt >= :from and p.validatedAt < :to)
            """)
    List<CollectionPayment> findTouchedByStaffInWindow(@Param("staffIds") Collection<Long> staffIds,
                                                       @Param("from") Instant from,
                                                       @Param("to") Instant to);
}
