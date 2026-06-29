package com.navix.loan.repository;

import com.navix.loan.entity.ApplicantProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for the per-application applicant KYC snapshot (1:1 with the application). */
@Repository
public interface ApplicantProfileRepository extends JpaRepository<ApplicantProfile, Long> {

    Optional<ApplicantProfile> findByApplicationId(Long applicationId);

    /** Batch-load profiles for a set of applications — used to enrich application list views. */
    List<ApplicantProfile> findByApplicationIdIn(Collection<Long> applicationIds);

    /**
     * The most recently captured profile for a mobile number (latest application wins). Used at
     * borrower login to resolve a returning borrower's display name from their stored KYC snapshot
     * instead of trusting a client-supplied name.
     */
    Optional<ApplicantProfile> findFirstByMobileOrderByApplicationIdDesc(String mobile);

    // --- identity uniqueness (a mobile/PAN/Aadhaar may belong to only one applicant) ---
    // Scoped to OTHER applicants: a profile is matched by joining its application to resolve the
    // owning applicantId, then excluding the queried applicant. This lets the SAME applicant
    // re-onboard through a new application (a second profile row carrying the same identity) while
    // still blocking a different person from reusing the PAN / Aadhaar / mobile.
    @Query("select (count(p) > 0) from ApplicantProfile p, LoanApplication a "
            + "where p.applicationId = a.id and p.pan = :pan and a.applicantId <> :applicantId")
    boolean existsPanForOtherApplicant(@Param("pan") String pan, @Param("applicantId") Long applicantId);

    @Query("select (count(p) > 0) from ApplicantProfile p, LoanApplication a "
            + "where p.applicationId = a.id and p.aadhaar = :aadhaar and a.applicantId <> :applicantId")
    boolean existsAadhaarForOtherApplicant(@Param("aadhaar") String aadhaar, @Param("applicantId") Long applicantId);

    @Query("select (count(p) > 0) from ApplicantProfile p, LoanApplication a "
            + "where p.applicationId = a.id and p.mobile = :mobile and a.applicantId <> :applicantId")
    boolean existsMobileForOtherApplicant(@Param("mobile") String mobile, @Param("applicantId") Long applicantId);
}
