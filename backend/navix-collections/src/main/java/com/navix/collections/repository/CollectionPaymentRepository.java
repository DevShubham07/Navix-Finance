package com.navix.collections.repository;

import com.navix.collections.entity.CollectionPayment;
import com.navix.collections.entity.CollectionPaymentStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for collections-side payments and their two-step approval (V47). */
@Repository
public interface CollectionPaymentRepository extends JpaRepository<CollectionPayment, UUID> {

    List<CollectionPayment> findByCollectionCaseIdOrderByRaisedAtDesc(UUID collectionCaseId);

    List<CollectionPayment> findByLoanIdOrderByRaisedAtDesc(Long loanId);

    /** The Accountant's queue, and the Collection Head's, depending on the status asked for. */
    List<CollectionPayment> findByStatusOrderByRaisedAtAsc(CollectionPaymentStatus status);

    List<CollectionPayment> findAllByOrderByRaisedAtDesc();
}
