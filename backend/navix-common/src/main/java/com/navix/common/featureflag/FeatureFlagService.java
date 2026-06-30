package com.navix.common.featureflag;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only access to dev-controlled feature flags (the {@code feature_flag} table).
 *
 * <p>Flags are changed <b>only</b> by a developer running SQL against the database — this service has
 * no write methods and there is no admin UI / write endpoint anywhere in the app. Reads hit the DB
 * directly (no caching) so a {@code UPDATE feature_flag ...} takes effect on the very next call, with
 * no redeploy or cache bust.
 *
 * <p>Any feature gates itself with {@code featureFlags.isEnabled("its-key")}; when the row is absent the
 * supplied default (or {@code false}) applies.
 */
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private final FeatureFlagRepository repository;

    /** Whether {@code key} is enabled, falling back to {@code defaultWhenMissing} when no row exists. */
    @Transactional(readOnly = true)
    public boolean isEnabled(String key, boolean defaultWhenMissing) {
        return repository.findByFlagKey(key)
                .map(FeatureFlag::isEnabled)
                .orElse(defaultWhenMissing);
    }

    /** Whether {@code key} is enabled; absent → {@code false} (feature off by default). */
    @Transactional(readOnly = true)
    public boolean isEnabled(String key) {
        return isEnabled(key, false);
    }

    /** All flags as {@code key → enabled} (for the read-only {@code GET /api/feature-flags} surface). */
    @Transactional(readOnly = true)
    public Map<String, Boolean> all() {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (FeatureFlag flag : repository.findAll()) {
            flags.put(flag.getFlagKey(), flag.isEnabled());
        }
        return flags;
    }
}
