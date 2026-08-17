package com.navix.loan.repository;

import com.navix.loan.entity.Lead;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/** Persistence for telecaller + DSA leads. */
@Repository
public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {

    /**
     * The DSA-owned lead (any DSA) already holding {@code pan}, if one exists — the cross-DSA
     * duplicate guard at lead-creation time (backed by {@code uq_lead_dsa_pan}).
     */
    Optional<Lead> findByPanAndOwnerDsaIdNotNull(String pan);

    /** A DSA's own leads, newest first — the hard ownership boundary for the DSA portal. */
    List<Lead> findByOwnerDsaIdOrderByIdDesc(Long ownerDsaId);

    /** Ownership-checked single-lead lookup — empty on a foreign id (never a distinguishable 403). */
    Optional<Lead> findByIdAndOwnerDsaId(Long id, Long ownerDsaId);

    /** Backs the daily lead-creation rate limit (successful creates only). */
    long countByOwnerDsaIdAndCreatedAtAfter(Long ownerDsaId, Instant after);
}
