package com.navix.common.blocklist;

/**
 * Write port onto the fraud blocklist ({@code blocklist_entry}, owned by {@code navix-iam}) for
 * modules that must not depend on IAM internals — the mirror of {@link
 * com.navix.common.staff.StaffDirectory} for writes instead of reads.
 *
 * <p>One caller today: {@code CustomerService.block} in {@code navix-loan}, which blocks a customer
 * for future loans in the rejection register and <em>mirrors</em> the block here so the dedupe
 * screen flags the same mobile / PAN on any later signup. Authorization is the caller's job (an
 * ACCOUNTANT may block; the IAM-side {@code BlocklistService} is ADMIN-only and is deliberately not
 * what this port reaches).
 */
public interface BlocklistMirror {

    /**
     * Add (or reactivate, with this reason) the active entry for {@code type} + {@code value}.
     * {@code type} is a {@code BlocklistType} name ({@code PHONE}, {@code PAN}, …); an unknown name
     * is ignored rather than failing the caller's transaction.
     */
    void block(String type, String value, String reason);

    /**
     * Deactivate the entry for {@code type} + {@code value} <b>only</b> when it carries exactly
     * {@code reason} — so lifting a staff block never lifts an entry an ADMIN added independently
     * for the same identifier. No entry, or a different reason, is a no-op.
     */
    void unblock(String type, String value, String reason);
}
