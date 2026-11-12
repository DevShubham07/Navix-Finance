package com.navix.common.blocklist;

/**
 * Published (synchronously, inside the removing transaction) when an ADMIN removes a blocklist
 * entry that is linked to a customer, so {@code navix-loan} can lift that customer's staff block once
 * nothing on the blocklist matches them any more.
 */
public record BlocklistEntryRemovedEvent(Long customerId) {
}
