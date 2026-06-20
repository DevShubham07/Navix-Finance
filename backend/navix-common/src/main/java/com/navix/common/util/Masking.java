package com.navix.common.util;

/**
 * Utility for masking sensitive identifiers (PAN, Aadhaar, phone) before they are
 * logged, returned to clients, or persisted. Privacy rule: the full Aadhaar number
 * is never stored; it is masked everywhere outside the verification step.
 *
 * TODO: finalize masking patterns and add unit tests for edge cases (nulls, short input).
 */
public final class Masking {

    private Masking() {
        // utility class - no instances
    }

    /**
     * Mask a PAN (e.g. ABCDE1234F -> ABXXXXX34F).
     * TODO: implement reveal of first 2 and last 3 characters only.
     */
    public static String maskPan(String pan) {
        // TODO: implement
        return pan;
    }

    /**
     * Mask an Aadhaar number, revealing only the last 4 digits (e.g. XXXX-XXXX-1234).
     * TODO: implement; never echo the full number.
     */
    public static String maskAadhaar(String aadhaar) {
        // TODO: implement
        return aadhaar;
    }

    /**
     * Mask a phone number, revealing only the last 4 digits (e.g. XXXXXX6789).
     * TODO: implement; handle country-code prefixes.
     */
    public static String maskPhone(String phone) {
        // TODO: implement
        return phone;
    }
}
