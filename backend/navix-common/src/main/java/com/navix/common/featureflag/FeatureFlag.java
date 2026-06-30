package com.navix.common.featureflag;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single dev-controlled feature flag — one row per named feature.
 *
 * <p>There is deliberately <b>no write path in the application</b> (no admin UI, no PUT/POST endpoint):
 * the table is changed only by a developer running SQL against the database (e.g.
 * {@code update feature_flag set enabled = false where flag_key = 'referral';}). The app only
 * <i>reads</i> these rows via {@link FeatureFlagService} to gate features at runtime — a change takes
 * effect immediately, with no redeploy.
 */
@Entity
@Table(name = "feature_flag")
@Getter
@Setter
@NoArgsConstructor
public class FeatureFlag extends BaseAuditEntity {

    /** Stable feature key referenced in code, e.g. {@code "referral"}. Unique. */
    @Column(name = "flag_key", nullable = false, length = 80)
    private String flagKey;

    /** Whether the feature is on. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Human note describing what the flag gates (for the dev reading the table). */
    @Column(name = "description", length = 255)
    private String description;
}
