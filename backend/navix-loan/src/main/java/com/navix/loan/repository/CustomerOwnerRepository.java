package com.navix.loan.repository;

import com.navix.loan.entity.CustomerOwner;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for sparse customer→staff ownership. */
@Repository
public interface CustomerOwnerRepository extends JpaRepository<CustomerOwner, Long> {

    /** Every customer one staffer owns — half of the "My customers" set. */
    @Query("select o.customerId from CustomerOwner o where o.ownerStaffId = :staffId")
    Set<Long> findCustomerIdsByOwnerStaffId(@Param("staffId") Long staffId);
}
