package com.navix.loan.repository;

import com.navix.loan.entity.ApplicationDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for borrower-uploaded application documents. */
@Repository
public interface ApplicationDocumentRepository extends JpaRepository<ApplicationDocument, Long> {

    List<ApplicationDocument> findByApplicationIdOrderByIdAsc(Long applicationId);

    Optional<ApplicationDocument> findByIdAndApplicationId(Long id, Long applicationId);

    /** The latest document of a given type for an application — used to upsert the CREDIT_BRIEF PDF. */
    Optional<ApplicationDocument> findFirstByApplicationIdAndDocTypeOrderByIdDesc(Long applicationId, String docType);
}
