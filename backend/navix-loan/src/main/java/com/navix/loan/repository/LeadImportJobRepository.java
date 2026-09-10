package com.navix.loan.repository;

import com.navix.loan.entity.LeadImportJob;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadImportJobRepository extends JpaRepository<LeadImportJob, Long> {

    /** "My recent imports" — the only listing the UI asks for. */
    List<LeadImportJob> findTop20ByUploadedByStaffIdOrderByIdDesc(Long uploadedByStaffId);

    /** The one-live-job-per-user guard: nine roles must not be able to dogpile a 1-vCPU task. */
    boolean existsByUploadedByStaffIdAndStatusIn(Long uploadedByStaffId, List<String> statuses);

    /** Boot-time reaper: desired count is 1, so a deploy kills whatever was mid-file. */
    List<LeadImportJob> findByStatusIn(List<String> statuses);
}
