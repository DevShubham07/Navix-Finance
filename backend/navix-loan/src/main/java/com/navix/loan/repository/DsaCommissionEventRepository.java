package com.navix.loan.repository;

import com.navix.loan.entity.DsaCommissionEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the DSA commission audit trail (V55). */
@Repository
public interface DsaCommissionEventRepository extends JpaRepository<DsaCommissionEvent, Long> {

    List<DsaCommissionEvent> findByCommissionIdOrderById(Long commissionId);
}
