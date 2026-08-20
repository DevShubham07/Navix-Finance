package com.navix.verification.support;

/**
 * One completed outbound call to an external verification provider (Signzy / Digitap), captured
 * verbatim for the admin Provider API dashboard.
 *
 * <p>{@code requestJson} and {@code responseJson} are the RAW wire payloads and therefore contain
 * PII (PAN, date of birth, mobile, name, bureau consent OTP). That is deliberate — the dashboard is
 * the place where an administrator can see exactly what we sent and exactly what came back — and it
 * is why the surface that reads these rows is ADMIN-only.
 *
 * @param provider      provider id, e.g. {@code DIGITAP} / {@code SIGNZY_CRIF}
 * @param operation     capability, e.g. {@code BUREAU} / {@code PAN} — same vocabulary as the
 *                      workbench catalog so manual and live rows filter identically
 * @param endpoint      provider path, e.g. {@code /credit_analytics/request}
 * @param requestJson   the serialized request body we sent
 * @param responseJson  the response body, or {@code null} when the call produced none
 * @param httpStatus    HTTP status, or {@code null} when the call never got one (timeout, DNS, …)
 * @param durationMs    wall-clock time spent in the call
 * @param status        {@code SUCCESS} or {@code FAILED}
 * @param errorMessage  failure detail, {@code null} on success
 */
public record ProviderCall(
        String provider,
        String operation,
        String endpoint,
        String requestJson,
        String responseJson,
        Integer httpStatus,
        long durationMs,
        String status,
        String errorMessage) {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    public boolean failed() {
        return FAILED.equals(status);
    }
}
