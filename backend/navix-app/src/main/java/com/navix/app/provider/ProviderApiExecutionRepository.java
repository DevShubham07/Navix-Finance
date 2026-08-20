package com.navix.app.provider;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProviderApiExecutionRepository extends JpaRepository<ProviderApiExecution, Long> {

    long deleteByExpiresAtBefore(Instant instant);

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
}
