package com.navix.common.notification;

/**
 * A resolved notification recipient with the addresses needed to deliver across channels.
 * {@code email}/{@code mobile} may be {@code null} — the dispatcher address-gates each channel, so a
 * missing address simply skips that channel (staff carry no mobile, so they never receive SMS).
 *
 * @param type the recipient kind (STAFF or BORROWER) — scopes in-app reads
 * @param id   staff id or customer id (the in-app inbox owner)
 * @param name human-readable name for templating
 * @param email contact email, or null
 * @param mobile 10-digit mobile, or null
 * @param role  the recipient's role (staff role, or "BORROWER")
 */
public record ContactInfo(
        RecipientType type,
        Long id,
        String name,
        String email,
        String mobile,
        String role) {
}
