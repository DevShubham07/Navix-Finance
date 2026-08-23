package com.navix.loan.repository;

import com.navix.loan.entity.ApplicationRejection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the rejection register (V44). */
@Repository
public interface ApplicationRejectionRepository extends JpaRepository<ApplicationRejection, Long> {

    List<ApplicationRejection> findAllByOrderByIdDesc();

    List<ApplicationRejection> findByReasonCodeOrderByIdDesc(String reasonCode);

    /** The rejection row(s) of one reason code on one application — used to clear only the
     *  LOW_BUREAU_SCORE block on a rescore-backfill reopen, leaving a MANUAL/SELF_EMPLOYED block
     *  on the same mobile untouched. */
    List<ApplicationRejection> findByApplicationIdAndReasonCode(Long applicationId, String reasonCode);

    /** The live cooling-off block on a mobile, if any — checked before every new application. */
    Optional<ApplicationRejection> findFirstByMobileAndBlockedUntilAfterOrderByBlockedUntilDesc(
            String mobile, Instant now);
}
