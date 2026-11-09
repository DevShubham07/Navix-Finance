package com.navix.loan.repository;

import com.navix.loan.entity.ReapplyAllowance;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReapplyAllowanceRepository extends JpaRepository<ReapplyAllowance, Long> {

    Optional<ReapplyAllowance> findFirstByCustomerIdAndUsedApplicationIdIsNullOrderByIdAsc(Long customerId);

    /** Consume exactly once (compare-and-set); 0 means someone else already did. */
    @Modifying(flushAutomatically = true)
    @Query("update ReapplyAllowance a set a.usedApplicationId = :appId, a.usedAt = :now "
            + "where a.id = :id and a.usedApplicationId is null")
    int consume(@Param("id") Long id, @Param("appId") Long appId, @Param("now") Instant now);
}
