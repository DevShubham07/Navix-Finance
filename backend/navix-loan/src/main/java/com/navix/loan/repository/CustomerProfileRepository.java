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
}
