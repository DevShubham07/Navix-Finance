package com.navix.loan.repository;

import com.navix.loan.entity.CustomerPennyDropLock;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the 12-hour penny-drop cool-off (V46). */
@Repository
public interface CustomerPennyDropLockRepository extends JpaRepository<CustomerPennyDropLock, Long> {

    Optional<CustomerPennyDropLock> findByCustomerId(Long customerId);

    void deleteByCustomerId(Long customerId);
}
