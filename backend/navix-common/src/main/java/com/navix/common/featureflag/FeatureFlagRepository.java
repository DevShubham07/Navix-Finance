package com.navix.common.featureflag;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read access to the {@link FeatureFlag} rows. Reads only — flags are mutated solely via direct SQL.
 */
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {

    /** The flag for a given key, if a row exists. */
    Optional<FeatureFlag> findByFlagKey(String flagKey);
}
