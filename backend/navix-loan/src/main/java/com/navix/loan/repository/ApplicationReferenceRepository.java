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

    /** Atomic so parallel requests cannot pass the send cap or the resend wait. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("""
            update ApplicationReference r set r.otpHash = :hash, r.otpExpiresAt = :expires, r.otpAttempts = 0,
                r.otpSendCount = r.otpSendCount + 1, r.otpLastSentAt = :now
            where r.id = :id and r.otpRequired = true and r.verifiedAt is null and r.otpSendCount < :maxSends
                and (r.otpLastSentAt is null or r.otpLastSentAt < :cutoff)
            """)
    int claimOtpSend(@Param("id") Long id, @Param("hash") String hash, @Param("expires") java.time.Instant expires,
                     @Param("now") java.time.Instant now, @Param("cutoff") java.time.Instant cutoff,
                     @Param("maxSends") int maxSends);

    /** Spends a guess before the code is compared, so parallel guesses cannot exceed the cap. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("""
            update ApplicationReference r set r.otpAttempts = r.otpAttempts + 1
            where r.id = :id and r.verifiedAt is null and r.otpHash is not null and r.otpExpiresAt > :now
                and r.otpAttempts < :maxAttempts
            """)
    int claimOtpAttempt(@Param("id") Long id, @Param("now") java.time.Instant now,
                        @Param("maxAttempts") int maxAttempts);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("update ApplicationReference r set r.verifiedAt = :now, r.otpHash = null where r.id = :id and r.verifiedAt is null")
    int markOtpVerified(@Param("id") Long id, @Param("now") java.time.Instant now);

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
