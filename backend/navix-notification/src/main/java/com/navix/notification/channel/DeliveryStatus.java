package com.navix.notification.channel;

/** Outcome of one channel delivery attempt, persisted on {@code notification_delivery.status}. */
public enum DeliveryStatus {
    /** Created but not yet attempted (transient). */
    PENDING,
    /** Handed to the channel transport (or recorded as sent in mock mode). */
    SENT,
    /** The transport raised an error. */
    FAILED,
    /** Not attempted — e.g. the recipient has no address for this channel, or it is disabled. */
    SKIPPED,
    /** Email only: SES reported a permanent bounce for this delivery (set by SesEventSqsListener). */
    BOUNCED,
    /** Email only: SES reported a complaint for this delivery (set by SesEventSqsListener). */
    COMPLAINED
}
