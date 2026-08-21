package com.navix.collections.repository;

import com.navix.collections.entity.InteractionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Persistence for {@link InteractionLog}. */
@Repository
public interface InteractionLogRepository extends JpaRepository<InteractionLog, UUID> {

    // TODO: implement query — interaction history for a case, most recent first.
    List<InteractionLog> findByCollectionCaseIdOrderByLoggedAtDesc(UUID collectionCaseId);

    /**
     * Interactions logged per staff member in the half-open window {@code [from, to)} — backs the
     * staff-performance "calls" metric via {@code CollectionActivityDirectoryAdapter}. Rides
     * {@code idx_interaction_log_staff_at} (V60). Rows with no {@code logged_by_staff_id} (anything
     * before V59) are excluded by the {@code in} clause rather than lumped under a null key.
     */
    @Query("""
            select i.loggedByStaffId as staffId, count(i) as count from InteractionLog i
            where i.loggedByStaffId in :staffIds and i.loggedAt >= :from and i.loggedAt < :to
            group by i.loggedByStaffId
            """)
    List<StaffCallCount> countByStaffInWindow(@Param("staffIds") Collection<Long> staffIds,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to);

    /** Projection for {@link #countByStaffInWindow(Collection, Instant, Instant)}. */
    interface StaffCallCount {
        Long getStaffId();

        Long getCount();
    }
}
