package com.navix.app.provider;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProviderApiExecutionRepository extends JpaRepository<ProviderApiExecution, Long> {

    long deleteByExpiresAtBefore(Instant instant);

    /**
     * Which providers were called for these applications, oldest first — the chain, without the
     * payloads. Backed by {@code ix_provider_api_execution_app (application_id, created_at desc)}.
     *
     * <p>Same projection discipline as {@link #search}: {@code request_json}/{@code response_json}
     * are omitted deliberately, because one bureau row carries a few hundred KB of credit report and
     * none of it is needed to say who was tried and what came back.
     */
    interface AttemptRow {
        Long getApplicationId();
        String getProvider();
        String getOperation();
        Integer getHttpStatus();
        String getStatus();
        Instant getCreatedAt();
    }

    @Query("""
            select r.applicationId as applicationId, r.provider as provider,
                   r.operation as operation, r.httpStatus as httpStatus, r.status as status,
                   r.createdAt as createdAt
            from ProviderApiExecution r
            where r.applicationId in :applicationIds
            order by r.applicationId asc, r.createdAt asc, r.id asc
            """)
    List<AttemptRow> findAttempts(@Param("applicationIds") Collection<Long> applicationIds);

    /**
     * Filtered history. Every filter is optional — a null parameter drops out of the predicate.
     *
     * <p>Deliberately a projection that OMITS {@code request_json}/{@code response_json}: a page of
     * bureau rows carries a few hundred KB of Experian report each, and shipping that through the BFF
     * to render a summary table would be megabytes per page. The payloads are fetched one row at a
     * time by {@code findById} when an administrator expands a row.
     */
    @Query("""
            select r.id as id, r.operation as operation, r.provider as provider, r.status as status,
                   r.httpStatus as httpStatus, r.durationMs as durationMs, r.source as source,
                   r.endpoint as endpoint, r.checkType as checkType, r.applicationId as applicationId,
                   r.requestId as requestId, r.errorMessage as errorMessage, r.createdAt as createdAt
            from ProviderApiExecution r
            where (:provider is null or r.provider = :provider)
              and (:operation is null or r.operation = :operation)
              and (:status is null or r.status = :status)
              and (:source is null or r.source = :source)
              and (:applicationId is null or r.applicationId = :applicationId)
              and (cast(:from as timestamp) is null or r.createdAt >= :from)
              and (cast(:to as timestamp) is null or r.createdAt <= :to)
            order by r.createdAt desc
            """)
    Page<ProviderApiExecutionSummary> search(@Param("provider") String provider,
                                             @Param("operation") String operation,
                                             @Param("status") String status,
                                             @Param("source") String source,
                                             @Param("applicationId") Long applicationId,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to,
                                             Pageable pageable);

    /** One row per (provider, operation) pair called since {@code since}. */
    interface HealthRow {
        String getProvider();
        String getOperation();
        long getCalls();
        long getSuccesses();
    }

    /**
     * Live-traffic success rate per capability, for {@code ProviderHealthMonitor}.
     *
     * <p>{@code source = 'LIVE'} deliberately: the ADMIN workbench probes a provider precisely when it
     * is suspected of being broken, and the employment retry sweep re-runs calls that already failed
     * once — both would drag a healthy capability's rate down. Reading the audit table rather than an
     * in-memory counter is what makes the check restart-proof and free: eSign makes roughly five calls
     * a day, so a process-local tracker would reset long before it noticed four weeks of total failure.
     *
     * <p>Only meaningful because of the audit-status fix shipped alongside it: before that, a Digitap
     * UAN "no record" answer was stored FAILED, and this query would have reported a permanent outage
     * on a capability behaving exactly as designed.
     */
    @Query(HEALTH_SINCE)
    List<HealthRow> healthSince(@Param("since") Instant since);

    String HEALTH_SINCE = "select r.provider as provider, r.operation as operation, "
            + "count(r) as calls, "
            + "sum(case when r.status = 'SUCCESS' then 1L else 0L end) as successes "
            + "from ProviderApiExecution r "
            + "where r.source = 'LIVE' and r.createdAt >= :since "
            + "group by r.provider, r.operation";
}
