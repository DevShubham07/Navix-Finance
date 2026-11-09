package com.navix.loan.payu;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface PayuOrderRepository extends JpaRepository<PayuOrder, Long> {

    Optional<PayuOrder> findByTxnid(String txnid);

    /** Row lock for the apply step; call inside a transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PayuOrder o where o.txnid = ?1")
    Optional<PayuOrder> lockByTxnid(String txnid);

    Optional<PayuOrder> findFirstByLoanIdOrderByCreatedAtDescIdDesc(Long loanId);

    List<PayuOrder> findByLoanIdAndStatusInOrderByCreatedAtDescIdDesc(Long loanId, Collection<PayuOrder.Status> statuses);

    /** Open orders whose next check is due, soonest first; one bounded page for the sweep. */
    @Query("select o from PayuOrder o where o.status in ?1 and o.nextCheckAt <= ?2 order by o.nextCheckAt")
    List<PayuOrder> findDue(Collection<PayuOrder.Status> statuses, Instant now, Pageable page);

    /** Review rows nobody has resolved yet, newest first (V94 partial index). */
    @Query("select o from PayuOrder o where o.reviewReason is not null and o.reviewResolvedAt is null"
            + " order by o.createdAt desc, o.id desc")
    List<PayuOrder> findUnresolvedReview(Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PayuOrder o where o.id = ?1")
    Optional<PayuOrder> lockById(Long id);
}
