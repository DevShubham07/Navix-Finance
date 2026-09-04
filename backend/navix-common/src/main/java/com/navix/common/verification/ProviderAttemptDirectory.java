package com.navix.common.verification;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Which providers were actually called for an application, in order, and what each one answered.
 *
 * <p><b>Why an interface here.</b> The record of every outbound provider call lives in
 * {@code provider_api_execution}, owned by {@code navix-app} — the bootable module, which depends on
 * {@code navix-loan} rather than the other way round. Staff-facing reads live in {@code navix-loan}
 * and cannot import it. Rather than duplicate the entity or reach across with native SQL, the
 * consumer depends on this and {@code navix-app} supplies the implementation, exactly as
 * {@code ProviderCallRecorder} and {@code ApplicationActorDirectory} already do.
 *
 * <p><b>Attempts carry no payloads.</b> Request and response bodies hold PAN, date of birth, mobile
 * and bureau consent OTPs, and stay in the ADMIN provider workbench, which is the one surface
 * authorised to show them. What travels here is only: who we called, for what, what status came back,
 * and when.
 *
 * <p><b>An empty list does not mean "never attempted".</b> Rows expire after 90 days, and the
 * applications most likely to be investigated are the oldest. Callers must say "no call history
 * retained" rather than "no attempts were made".
 */
public interface ProviderAttemptDirectory {

    /**
     * How long attempt rows are kept. Surfaced to staff so an empty list reads as "no call history
     * retained" rather than "we never tried" — the distinction matters most on the oldest, most
     * investigated applications, which are exactly the ones past the window.
     */
    int RETENTION_DAYS = 90;

    /**
     * One recorded call.
     *
     * @param provider    provider id, e.g. {@code FINTRIX} / {@code DIGITAP}
     * @param operation   capability, e.g. {@code BUREAU} / {@code PAN}
     * @param httpStatus  status returned, or {@code null} when the call never got one (timeout, reset,
     *                    or a body that could not be read)
     * @param succeeded   whether the call itself completed — note a provider can answer 200 with a
     *                    business "no record", which is a success here and not an error
     * @param at          when the call was made
     */
    record Attempt(String provider, String operation, Integer httpStatus, boolean succeeded,
                   Instant at) {
    }

    /** Attempts for each application, oldest first — the order is what makes a chain readable. */
    Map<Long, List<Attempt>> byApplicationId(Collection<Long> applicationIds);
}
