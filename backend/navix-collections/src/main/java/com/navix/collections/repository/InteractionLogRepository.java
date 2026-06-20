package com.navix.collections.repository;

import com.navix.collections.entity.InteractionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Persistence for {@link InteractionLog}. */
@Repository
public interface InteractionLogRepository extends JpaRepository<InteractionLog, UUID> {

    // TODO: implement query — interaction history for a case, most recent first.
    List<InteractionLog> findByCollectionCaseIdOrderByLoggedAtDesc(UUID collectionCaseId);
}
