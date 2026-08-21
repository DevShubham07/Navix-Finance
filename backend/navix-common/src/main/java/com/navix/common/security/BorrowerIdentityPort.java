package com.navix.common.security;

/**
 * Resolves whether a mobile number would collide with a DIFFERENT borrower's already-claimed login
 * identity. Implemented by the auth module ({@code navix-app}), which owns the
 * {@code borrower_mobile} claim table and the mobile-to-customer-id derivation — the same reason
 * {@code OtpVerifierPort} exists: {@code navix-app} sits ABOVE the domain modules in the dependency
 * graph, so {@code navix-loan} cannot reach it directly.
 *
 * <p>Login identity is derived from a mobile by keeping only its last 7 digits (see
 * {@code AuthController.deriveCustomerId}), so two DIFFERENT 10-digit numbers can derive the SAME
 * id — a plain string-equality check on the raw mobile is not enough to catch this. This port exists
 * specifically to close that gap for admin-initiated KYC-data corrections, where the number being
 * saved did not go through the borrower's own login/claim flow.
 */
public interface BorrowerIdentityPort {

    /**
     * @param mobile     the number being considered — any digit-bearing string; non-digit
     *                   characters are stripped before deriving the id.
     * @param customerId the customer this mobile is being saved onto.
     * @return true when {@code mobile} derives a login identity already claimed by a DIFFERENT
     *     customer than {@code customerId} — saving it here would make every lookup keyed on that
     *     derived id (forgot-password, login display name) ambiguous between two real borrowers.
     *     False when the derived id is unclaimed, or already claimed by {@code customerId} itself.
     */
    boolean wouldCollideWithAnotherCustomer(String mobile, Long customerId);
}
