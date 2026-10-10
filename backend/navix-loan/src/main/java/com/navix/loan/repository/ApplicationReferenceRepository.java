package com.navix.loan.repository;

import com.navix.loan.entity.ApplicationReference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for the two contacts the borrower names in Phase 3 (V46). */
@Repository
public interface ApplicationReferenceRepository extends JpaRepository<ApplicationReference, Long> {

    List<ApplicationReference> findByApplicationIdOrderBySlotAsc(Long applicationId);

    /** Batched twin of the above, for the collections handover export. */
    List<ApplicationReference> findByApplicationIdInOrderByApplicationIdAscSlotAsc(
            java.util.Collection<Long> applicationIds);

    Optional<ApplicationReference> findByApplicationIdAndSlot(Long applicationId, Short slot);

    List<ApplicationReference> findByCustomerId(Long customerId);

    /**
     * References OTHER customers gave with any of {@code mobiles}, newest application first;
     * [reference, borrowerName, applicationStatus] — name from the application's profile.
     */
    @Query("select r, (select p.fullName from CustomerProfile p where p.applicationId = r.applicationId), "
            + "a.status from ApplicationReference r, LoanApplication a "
            + "where r.applicationId = a.id and r.mobile in :mobiles and r.customerId <> :customerId "
            + "order by r.applicationId desc, r.slot asc")
    List<Object[]> findOtherReferencesByMobileIn(@Param("mobiles") java.util.Collection<String> mobiles,
                                                 @Param("customerId") Long customerId);
}
