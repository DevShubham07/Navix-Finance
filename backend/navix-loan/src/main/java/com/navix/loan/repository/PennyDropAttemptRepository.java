package com.navix.loan.repository;

import com.navix.loan.entity.PennyDropAttempt;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for penny-drop attempts — the audit behind the 3-strikes rule (V46). */
@Repository
public interface PennyDropAttemptRepository extends JpaRepository<PennyDropAttempt, Long> {

    /** Newest first; the strike count walks this until it meets a success. */
    List<PennyDropAttempt> findByCustomerIdOrderByIdDesc(Long customerId);
}
