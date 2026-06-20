package com.navix.iam.repository;

import com.navix.iam.domain.BlocklistType;
import com.navix.iam.entity.BlocklistEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link BlocklistEntry}.
 *
 * TODO: add a query for active entries by type for bulk screening.
 */
public interface BlocklistEntryRepository extends JpaRepository<BlocklistEntry, Long> {

    boolean existsByTypeAndValueAndActiveTrue(BlocklistType type, String value);

    Optional<BlocklistEntry> findByTypeAndValue(BlocklistType type, String value);

    List<BlocklistEntry> findByActiveTrue();
}
