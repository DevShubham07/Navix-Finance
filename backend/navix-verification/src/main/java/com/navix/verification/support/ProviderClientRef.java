package com.navix.verification.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers the application id and check type from the client reference we embed in every provider
 * payload. {@code ApplicationVerificationService} mints these as {@code navix-{appId}-{CHECK_TYPE}}
 * (e.g. {@code navix-116-BUREAU}).
 *
 * <p>This is the FALLBACK attribution path, used when the request-scoped
 * {@link ProviderCallContext} is empty — for instance on a provider callback that arrives on its own
 * thread. It matches on the value's shape rather than a field name, because the field is called
 * {@code client_ref_num} at Digitap and something else at Signzy.
 */
public final class ProviderClientRef {

    private static final Pattern CLIENT_REF = Pattern.compile("navix-([0-9]+)-([A-Z_]+)");

    private ProviderClientRef() {
        // static holder - no instances
    }

    /** The application id embedded in {@code payload}, or {@code null} if there is none. */
    public static Long applicationId(String payload) {
        Matcher matcher = matcher(payload);
        if (matcher == null) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException absurdlyLongId) {
            return null;
        }
    }

    /** The check type embedded in {@code payload}, or {@code null} if there is none. */
    public static String checkType(String payload) {
        Matcher matcher = matcher(payload);
        return matcher == null ? null : matcher.group(2);
    }

    private static Matcher matcher(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Matcher matcher = CLIENT_REF.matcher(payload);
        return matcher.find() ? matcher : null;
    }
}
