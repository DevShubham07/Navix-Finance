package com.navix.loan.repository;

import com.navix.loan.entity.ApplicantProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the per-application applicant KYC snapshot (1:1 with the application). */
@Repository
public interface ApplicantProfileRepository extends JpaRepository<ApplicantProfile, Long> {

    Optional<ApplicantProfile> findByApplicationId(Long applicationId);

    // --- identity uniqueness (a mobile/PAN/Aadhaar may belong to only one applicant) ---
    // "...ApplicationIdNot" excludes the profile's own application so re-saving it is fine.
    boolean existsByPanAndApplicationIdNot(String pan, Long applicationId);

    boolean existsByAadhaarAndApplicationIdNot(String aadhaar, Long applicationId);

    boolean existsByMobileAndApplicationIdNot(String mobile, Long applicationId);
}
