package com.navix.loan.repository;

import com.navix.loan.entity.BorrowerPreferences;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for borrower notification preferences (1:1 with customer_id). */
@Repository
public interface BorrowerPreferencesRepository extends JpaRepository<BorrowerPreferences, Long> {

    Optional<BorrowerPreferences> findByCustomerId(Long customerId);
}
