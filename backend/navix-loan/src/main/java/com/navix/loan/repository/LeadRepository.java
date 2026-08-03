package com.navix.loan.repository;

import com.navix.loan.entity.Lead;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for telecaller leads. */
@Repository
public interface LeadRepository extends JpaRepository<Lead, Long> {

    @Query("""
            SELECT l FROM Lead l
            WHERE (:q IS NULL OR :q = ''
                   OR LOWER(l.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR l.mobile LIKE CONCAT('%', :q, '%'))
              AND (:callStatus IS NULL OR :callStatus = '' OR l.callStatus = :callStatus)
              AND (:source IS NULL OR :source = '' OR l.source = :source)
              AND (:createdBy IS NULL OR l.createdByStaffId = :createdBy)
              AND (:from IS NULL OR l.createdAt >= :from)
              AND (:to IS NULL OR l.createdAt < :to)
              AND (:minRating IS NULL OR l.qualityRating >= :minRating)
              AND (:maxRating IS NULL OR l.qualityRating <= :maxRating)
            ORDER BY l.id DESC
            """)
    List<Lead> search(
            @Param("q") String q,
            @Param("callStatus") String callStatus,
            @Param("source") String source,
            @Param("createdBy") Long createdBy,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("minRating") Integer minRating,
            @Param("maxRating") Integer maxRating);
}
