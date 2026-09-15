package com.navix.loan.repository;

import com.navix.loan.entity.Lead;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Everything a DSA may SEE: leads they own (entered, commission-eligible) plus leads they merely
     * uploaded (V70). An imported row carries {@code created_by_staff_id} but deliberately no
     * {@code owner_dsa_id}, so a bulk upload can never manufacture commission — visibility and
     * ownership are different questions and this is the visibility one.
     *
     * <p>Both sides are needed: a DSA's own entry sets both columns, but ADMIN's {@code correctLead}
     * can reassign {@code owner_dsa_id} to a DSA who did not create the row.
     */
    List<Lead> findByOwnerDsaIdOrCreatedByStaffIdOrderByIdDesc(Long ownerDsaId, Long createdByStaffId);

    /** The single-lead form of the above — same "empty, never a distinguishable 403" property. */
    @Query("select l from Lead l where l.id = :id "
            + "and (l.ownerDsaId = :dsaId or l.createdByStaffId = :dsaId)")
    Optional<Lead> findVisibleToDsa(@Param("id") Long id, @Param("dsaId") Long dsaId);

    /** Backs the daily lead-creation rate limit (successful creates only). */
    long countByOwnerDsaIdAndCreatedAtAfter(Long ownerDsaId, Instant after);

    /** Batch duplicate lookup for CSV import — the admin upload's "matches an existing lead" check. */
    List<Lead> findByMobileIn(Collection<String> mobiles);

    /** Batch duplicate lookup for CSV import, PAN side. */
    List<Lead> findByPanIn(Collection<String> pans);
}
