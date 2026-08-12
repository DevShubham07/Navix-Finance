package com.navix.loan.repository;

import com.navix.loan.entity.ApplicationEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
