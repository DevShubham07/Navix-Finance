package com.navix.loan.repository;

import com.navix.loan.entity.RepaymentDiscountOffer;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RepaymentDiscountOfferRepository extends JpaRepository<RepaymentDiscountOffer, Long> {

    List<RepaymentDiscountOffer> findByCustomerIdAndUsedApplicationIdIsNull(Long customerId);

    /**
     * Claim an offer exactly once: the WHERE makes the update a compare-and-set, so a second claim
     * (another application, a retry) matches no row and returns 0. Does not clear the persistence
     * context — the caller (loan disbursal) is mid-transaction with managed entities.
     */
    @Modifying(flushAutomatically = true)
    @Query("update RepaymentDiscountOffer o set o.usedApplicationId = :appId, o.usedLoanId = :loanId, "
            + "o.usedAt = :now where o.id = :id and o.usedApplicationId is null")
    int claim(@Param("id") Long id, @Param("appId") Long appId, @Param("loanId") Long loanId,
              @Param("now") Instant now);
}
