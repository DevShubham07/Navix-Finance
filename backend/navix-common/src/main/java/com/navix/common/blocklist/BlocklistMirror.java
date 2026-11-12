package com.navix.common.blocklist;

import java.util.Collection;
import java.util.Optional;

/**
 * Port onto the fraud blocklist ({@code blocklist_entry}, owned by {@code navix-iam}) for modules
 * that must not depend on IAM internals — the mirror of {@link com.navix.common.staff.StaffDirectory}.
 *
 * <p>The blocklist is the single source of truth for "is this person blocked": a customer is blocked
 * for new loans when they hold a staff block ({@code BLOCKED_BY_STAFF} rejection row) <b>or</b> any
 * of their identifiers is an active entry. "Block customer" writes entries linked to the customer;
 * unblocking a customer clears every entry that matches them; removing a customer's last entry on the
 * blocklist page lifts their staff block ({@link BlocklistEntryRemovedEvent}). Authorization is the
 * caller's job — the IAM-side {@code BlocklistService} is ADMIN-only and is not what this port reaches.
 */
public interface BlocklistMirror {

    /**
     * Add (or reactivate) the active entry for {@code type} + {@code value}, linked to
     * {@code customerId}. An entry that is already active is left as it is (it belongs to whoever
     * created it). An unknown type or a blank value is ignored rather than failing the caller.
     */
    void block(String type, String value, String reason, Long customerId);

    /** The type of the first active entry matching any of {@code keys}, if there is one. */
    Optional<String> firstActiveMatch(Collection<BlocklistKey> keys);

    /** True when an active entry is linked to {@code customerId}. */
    boolean hasActiveLinked(Long customerId);

    /**
     * Deactivate every active entry linked to {@code customerId} or matching any of {@code keys}.
     *
     * @return how many entries were deactivated
     */
    int deactivateAll(Long customerId, Collection<BlocklistKey> keys);
}
