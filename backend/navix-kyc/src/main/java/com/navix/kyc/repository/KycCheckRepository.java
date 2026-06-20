package com.navix.kyc.repository;

import com.navix.kyc.entity.KycCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Persistence for {@link KycCheck}.
 * TODO: add finders by kycCaseId and type as the orchestration needs them.
 */
@Repository
public interface KycCheckRepository extends JpaRepository<KycCheck, Long> {

    List<KycCheck> findByKycCaseId(Long kycCaseId);
}
