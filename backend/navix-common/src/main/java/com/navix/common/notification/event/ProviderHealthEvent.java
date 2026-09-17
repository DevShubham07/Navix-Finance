package com.navix.common.notification.event;

import java.time.Instant;

/**
 * A verification provider is unwell in a way only an operator can fix.
 *
 * <p>Two kinds, both raised by {@code ProviderHealthMonitor} in {@code navix-app}:
 *
 * <ul>
 *   <li>{@link Kind#BALANCE_EXHAUSTED} — a prepaid account hit zero, so EVERY call to that provider
 *       fails until it is topped up. Production sat at zero for 11.5 hours in Aug 2026 and 106
 *       applications lost their bureau pull behind a generic {@code HTTP_402}; the only trace was a
 *       log line nobody was watching.</li>
 *   <li>{@link Kind#CAPABILITY_DOWN} — one provider operation has not succeeded once in 24 hours
 *       across at least ten live calls. Aadhaar eSign was in exactly this state for four weeks
 *       (156 attempts, 0 successes) and raised nothing, because each individual failure was caught
 *       and degraded into a fallback.</li>
 * </ul>
 *
 * <p>Like {@link PaymentReminderEvent} this is <b>not</b> tied to a business transaction — the
 * balance alert fires from inside a provider call whose surrounding transaction may well roll back —
 * so its listener is a plain {@code @Async @EventListener}, never {@code AFTER_COMMIT}.
 *
 * @param kind      which of the two conditions above
 * @param provider  the provider name as the audit table spells it ({@code SIGNZY}, {@code DIGITAP}…)
 * @param operation the audit table's operation name, or {@code null} for a balance alert (which is
 *                  account-wide, not per-operation)
 * @param endpoint  the URL that revealed the condition
 * @param calls     how many live calls the window counted (0 for a balance alert)
 * @param since     the start of the window the counts were taken over
 */
public record ProviderHealthEvent(
        Kind kind,
        String provider,
        String operation,
        String endpoint,
        int calls,
        Instant since) {

    public enum Kind {
        BALANCE_EXHAUSTED,
        CAPABILITY_DOWN
    }
}
