package com.navix.common.security;

/**
 * The staff/borrower identity performing the current request. In demo mode this is resolved
 * from request headers (no real authentication yet); at go-live it will come from the verified
 * JWT principal. Carries enough to populate audit fields and enforce separation-of-duties.
 *
 * @param id   stable identifier of the actor (e.g. staff user id)
 * @param name human-readable name, used for audit/created-by
 * @param role the actor's role (e.g. CREDIT_HEAD, ACCOUNTANT, ADMIN, BORROWER)
 */
public record CurrentActor(String id, String name, String role) {

    /** Fallback actor for non-web contexts (jobs, tests) where no request identity is bound. */
    public static final CurrentActor SYSTEM = new CurrentActor("system", "system", "SYSTEM");
}
