package com.navix.loan.repository;

import com.navix.loan.entity.BureauBackfillRow;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the bureau-backfill outcome ledger (V62). */
@Repository
public interface BureauBackfillRowRepository extends JpaRepository<BureauBackfillRow, Long> {

    List<BureauBackfillRow> findByRunId(String runId);

    /** The most recent attempt on this application, across every run — resumability reads this: a
     *  non-{@code FAILED} row means "already processed, skip"; a {@code FAILED} (or absent) row means
     *  "attempt it". */
    Optional<BureauBackfillRow> findFirstByApplicationIdOrderByIdDesc(Long applicationId);
}
