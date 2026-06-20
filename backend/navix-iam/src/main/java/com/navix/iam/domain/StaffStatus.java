package com.navix.iam.domain;

/**
 * Lifecycle status of a staff user account.
 * TODO: define allowed transitions (INVITED -> ACTIVE, ACTIVE <-> DISABLED).
 */
public enum StaffStatus {
    ACTIVE,
    INVITED,
    DISABLED
}
