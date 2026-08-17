package com.navix.loan.repository;

import com.navix.loan.entity.CustomerProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for the per-application customer KYC snapshot (1:1 with the application). */
@Repository
public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, Long> {

    Optional<CustomerProfile> findByApplicationId(Long applicationId);

    /** Batch-load profiles for a set of applications — used to enrich application list views. */
    List<CustomerProfile> findByApplicationIdIn(Collection<Long> applicationIds);

    /**
     * The most recently captured profile for a mobile number (latest application wins). Used at
     * borrower login to resolve a returning borrower's display name from their stored KYC snapshot
     * instead of trusting a client-supplied name.
     */
    Optional<CustomerProfile> findFirstByMobileOrderByApplicationIdDesc(String mobile);

    // --- identity uniqueness (a mobile/PAN may belong to only one customer) ---
    // Scoped to OTHER customers: a profile is matched by joining its application to resolve the
    // owning customerId, then excluding the queried customer. This lets the SAME customer
    // re-onboard through a new application (a second profile row carrying the same identity) while
    // still blocking a different person from reusing the PAN / mobile.
    @Query("select (count(p) > 0) from CustomerProfile p, LoanApplication a "
            + "where p.applicationId = a.id and p.pan = :pan and a.customerId <> :customerId")
    boolean existsPanForOtherCustomer(@Param("pan") String pan, @Param("customerId") Long customerId);

    @Query("select (count(p) > 0) from CustomerProfile p, LoanApplication a "
            + "where p.applicationId = a.id and p.mobile = :mobile and a.customerId <> :customerId")
    boolean existsMobileForOtherCustomer(@Param("mobile") String mobile, @Param("customerId") Long customerId);

    /**
     * Every mobile this customer has on file across their applications, newest application first.
     * A returning borrower who starts a fresh application while already signed in never passes back
     * through the mobile step, so that application's profile has no mobile of its own — this recovers
     * it from the customer's earlier profile instead of dead-ending the bureau-consent step.
     */
    @Query("select p.mobile from CustomerProfile p, LoanApplication a "
            + "where p.applicationId = a.id and a.customerId = :customerId "
            + "and p.mobile is not null and p.mobile <> '' order by p.applicationId desc")
    List<String> findMobilesForCustomer(@Param("customerId") Long customerId);

    /**
     * Whether {@code pan} already belongs to ANY existing customer — unlike
     * {@link #existsPanForOtherCustomer}, which is scoped to "other than this customer id" and so
     * can't answer "is this PAN known to us at all". Backs the DSA lead-creation entry-time
     * rejection (spec: a PAN belonging to an existing customer is rejected the same as a PAN
     * already held by another DSA's lead).
     */
    boolean existsByPan(String pan);

    /**
     * The single earliest {@link com.navix.loan.entity.LoanApplication} created strictly after
     * {@code after} for a customer whose profile carries {@code pan} — the DSA attribution query.
     * Pinning to the earliest post-lead application (not the latest, and never a pre-existing
     * in-flight one) is what keeps a customer's repeat borrowing invisible to the DSA.
     */
    @Query("select a from CustomerProfile p, LoanApplication a "
            + "where p.applicationId = a.id and p.pan = :pan and a.createdAt > :after "
            + "order by a.createdAt asc, a.id asc")
    List<com.navix.loan.entity.LoanApplication> findApplicationsAfterByPan(
            @Param("pan") String pan, @Param("after") java.time.Instant after);
}
