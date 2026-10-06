package com.navix.iam.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle status of a staff user account. Each enum value declares the states it may legally
 * transition to; {@link #canTransitionTo} enforces it server-side, mirroring
 * {@code ApplicationStatus.canTransitionTo} in navix-loan.
 */
public enum StaffStatus {
    ACTIVE,
    INVITED,
    DISABLED;

    private static final Map<StaffStatus, Set<StaffStatus>> TRANSITIONS = new EnumMap<>(StaffStatus.class);

    static {
        // Idempotent same→same, plus ACTIVE <-> DISABLED and the one-way INVITED -> ACTIVE/DISABLED.
        // Nothing transitions BACK to INVITED — an account only ever arrives there via the invite flow.
        TRANSITIONS.put(ACTIVE, EnumSet.of(ACTIVE, DISABLED));
        TRANSITIONS.put(INVITED, EnumSet.of(INVITED, ACTIVE, DISABLED));
        TRANSITIONS.put(DISABLED, EnumSet.of(DISABLED, ACTIVE));
    }

    /** Whether this status may legally transition to {@code next}. */
    public boolean canTransitionTo(StaffStatus next) {
        return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(StaffStatus.class)).contains(next);
    }
}
