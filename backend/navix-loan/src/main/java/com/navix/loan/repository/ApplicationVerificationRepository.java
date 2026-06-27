package com.navix.loan.repository;

import com.navix.loan.entity.ApplicationVerification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for per-application external verification results (idempotent per check type). */
@Repository
public interface ApplicationVerificationRepository extends JpaRepository<ApplicationVerification, Long> {

    Optional<ApplicationVerification> findByApplicationIdAndCheckType(Long applicationId, String checkType);

    List<ApplicationVerification> findByApplicationIdOrderByIdAsc(Long applicationId);
}
