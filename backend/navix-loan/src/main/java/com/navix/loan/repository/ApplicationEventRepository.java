package com.navix.loan.repository;

import com.navix.loan.entity.ApplicationEvent;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for the application approval/audit trail. */
@Repository
public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, Long> {

    List<ApplicationEvent> findByApplicationIdOrderByAtAsc(Long applicationId);

    /** All events with a given action label (e.g. CREATE) — backs the dashboard trend aggregation. */
    List<ApplicationEvent> findByAction(String action);

    /** One staffer's actions, newest first — backs the decision history (/staff/my-decisions). */
    List<ApplicationEvent> findByActorIdOrderByAtDesc(String actorId);

    /** Batched "latest activity" lookup for a set of applications (telecalling staleness), newest
     *  event first per row so the first hit per {@code applicationId} in iteration order is the latest. */
    List<ApplicationEvent> findByApplicationIdInOrderByAtDesc(List<Long> applicationIds);

    /**
     * Every event by any of {@code actorIds} in the half-open window {@code [from, to)} — backs the
     * staff-performance summary.
     *
     * <p>Both bounds are required rather than nullable-for-"all time": Postgres cannot infer the
     * type of a bare {@code :param is null} test and rejects the statement outright. The caller has
     * exact real bounds to pass anyway — nothing predates the epoch and nothing is logged in the
     * future — so an unbounded read needs no special case here.
     *
     * <p>Scoped by actor rather than by date alone so it rides {@code idx_application_event_actor_at}
     * (V59); the roster is always a bounded list of staff ids, even for ADMIN.
     */
    @Query("""
            select e from ApplicationEvent e
            where e.actorId in :actorIds and e.at >= :from and e.at < :to
            """)
    List<ApplicationEvent> findForActorsInWindow(@Param("actorIds") Collection<String> actorIds,
                                                 @Param("from") Instant from,
                                                 @Param("to") Instant to);
}
