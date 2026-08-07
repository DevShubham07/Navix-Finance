package com.navix.loan.repository;

import com.navix.loan.entity.ApplicationReference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the two contacts the borrower names in Phase 3 (V46). */
@Repository
public interface ApplicationReferenceRepository extends JpaRepository<ApplicationReference, Long> {

    List<ApplicationReference> findByApplicationIdOrderBySlotAsc(Long applicationId);

    Optional<ApplicationReference> findByApplicationIdAndSlot(Long applicationId, Short slot);
}
