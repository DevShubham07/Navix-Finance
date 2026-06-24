package com.navix.common.staff;

import java.util.List;
import java.util.Optional;

/**
 * Read-only port for looking up staff, implemented by the IAM module
 * ({@code navix-iam}). It lets other modules (e.g. {@code navix-loan},
 * {@code navix-collections}) validate or render an assignee without depending on
 * IAM internals — the same "swap at a seam" pattern used for identity. At go-live
 * the implementation can move behind a cache or a remote IdP without touching callers.
 */
public interface StaffDirectory {

    /**
     * True iff a staff user with {@code staffId} exists, is ACTIVE, and holds the
     * given role (e.g. {@code "CREDIT_EXECUTIVE"}). Used to enforce activation
     * gating on assignee pickers (dfd.md §13.4): a Head may assign work only to an
     * active staff member who holds the required role.
     */
    boolean isActiveWithRole(Long staffId, String role);

    /** The staff member with {@code staffId}, or empty if none. For rendering names. */
    Optional<StaffSummary> findStaff(Long staffId);

    /**
     * All ACTIVE staff holding {@code role} (a {@code StaffRole} name), for assignee
     * pickers (activation gating). An unknown role name yields an empty list.
     */
    List<StaffSummary> listActive(String role);
}
