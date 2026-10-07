package com.navix.common.notification.event;

import java.time.Instant;

/** Published once when a borrower submits the onboarding-paused waitlist form (V76). */
public record WaitlistSubmittedEvent(Long customerId, Instant at) {
}
