package com.navix.kyc.repository;

import com.navix.kyc.entity.KycCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Persistence for {@link KycCase}.
 * TODO: add finder by borrowerId for the active case.
 */
@Repository
public interface KycCaseRepository extends JpaRepository<KycCase, Long> {

    Optional<KycCase> findByBorrowerId(Long borrowerId);
}
