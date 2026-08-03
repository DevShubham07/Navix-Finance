package com.navix.loan.repository;

import com.navix.loan.entity.CustomerOwner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for sparse customer→staff ownership. */
@Repository
public interface CustomerOwnerRepository extends JpaRepository<CustomerOwner, Long> {
}
