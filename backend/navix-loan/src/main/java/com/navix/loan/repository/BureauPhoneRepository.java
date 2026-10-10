package com.navix.loan.repository;

import com.navix.loan.entity.BureauPhone;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BureauPhoneRepository extends JpaRepository<BureauPhone, Long> {

    @Modifying
    @Query("delete from BureauPhone b where b.applicationId = :applicationId")
    void deleteByApplicationId(@Param("applicationId") Long applicationId);

    /** Other customers' bureau reports listing any of {@code mobiles}; mismatched (maybe-stranger) reports excluded. */
    @Query("select b from BureauPhone b where b.mobile in :mobiles and b.customerId <> :customerId "
            + "and b.identityMismatch = false order by b.applicationId desc")
    List<BureauPhone> findOtherCustomersByMobileIn(@Param("mobiles") Collection<String> mobiles,
                                                   @Param("customerId") Long customerId);
}
