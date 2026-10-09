package com.navix.common.security;

/**
 * The staff/borrower identity performing the current request, resolved from the verified JWT
 * principal. Carries enough to populate audit fields and enforce separation-of-duties.
 *
 * @param id         stable identifier of the actor (e.g. staff user id)
 * @param name       human-readable name, used for audit/created-by
 * @param role       the actor's REAL role (e.g. CREDIT_HEAD, ACCOUNTANT, ADMIN, BORROWER) — used for
 *                   all authorization
 * @param actingRole the validated "work as" sub-role from {@code X-Acting-Role}, or null. Used for
 *                   list scoping and audit only, never for authorization
 */
public record CurrentActor(String id, String name, String role, String actingRole) {

    public CurrentActor(String id, String name, String role) {
        this(id, name, role, null);
    }

    /** The role whose view the caller is working in: the acting role if set, else the real role. */
    public String effectiveRole() {
        return actingRole != null ? actingRole : role;
    }

    /** Fallback actor for non-web contexts (jobs, tests) where no request identity is bound. */
    public static final CurrentActor SYSTEM = new CurrentActor("system", "system", "SYSTEM");
}
