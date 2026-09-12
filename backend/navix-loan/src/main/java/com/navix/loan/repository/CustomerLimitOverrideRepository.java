package com.navix.loan.repository;

import com.navix.loan.entity.CustomerLimitOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the sparse ADMIN per-customer eligible-limit override (V69). */
@Repository
public interface CustomerLimitOverrideRepository extends JpaRepository<CustomerLimitOverride, Long> {
}
