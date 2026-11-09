package com.navix.common.notification.event;

import java.time.Instant;

/**
 * Published when a borrower's bank cannot register a NACH eMandate through the API, so the offer journey
 * parks the attempt as {@code BANK_UNSUPPORTED}. The borrower is told our team will be in touch — this is
 * what actually tells them: a {@code MANDATE_BANK_UNSUPPORTED} alert to the Disbursement Heads, who
 * complete the mandate out of band and then override the gate from the application's Mandate tab.
 *
 * <p>Carried inline (the async listener has no transaction and no {@code ActorContext}).
 */
public record MandateBankUnsupportedEvent(
        Long customerId,
        Long applicationId,
        String bankName,
        Instant at) {
}
