package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Staff asked us to invite a customer to set up auto-repayment (a NACH eMandate) outside the offer
 * journey — typically because their loan is already live, so they never saw the mandate step.
 *
 * <p>Carries a {@code link} to OUR page, never Digio's {@code authentication_url}: that url expires
 * (navix.digio.expire-in-days) and belongs to one attempt, so a message carrying it would rot in the
 * borrower's inbox. Our page mints a fresh attempt whenever they open it.
 */
public record MandateInviteEvent(Long customerId, Long applicationId, String name, String link, Instant at) {
}
