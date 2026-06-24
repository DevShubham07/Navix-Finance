package com.navix.common.util;

/**
 * Utility for masking sensitive identifiers (PAN, Aadhaar, phone, email) before they are
 * logged, returned to clients, or persisted. Privacy rule: the full Aadhaar number is never
 * stored; it is masked everywhere outside the verification step.
 */
public final class Masking {

    private static final String MASK_CHAR = "X";

    private Masking() {
        // utility class - no instances
    }

    /** Mask a PAN, revealing the first 2 and last 3 characters (e.g. ABCDE1234F -> ABXXXXX34F). */
    public static String maskPan(String pan) {
        if (pan == null) {
            return null;
        }
        String trimmed = pan.trim();
        if (trimmed.length() <= 5) {
            return MASK_CHAR.repeat(trimmed.length());
        }
        String first = trimmed.substring(0, 2);
        String last = trimmed.substring(trimmed.length() - 3);
        return first + MASK_CHAR.repeat(trimmed.length() - 5) + last;
    }

    /** Mask an Aadhaar number, revealing only the last 4 digits (e.g. XXXXXXXX1234). */
    public static String maskAadhaar(String aadhaar) {
        return maskAllButLast(aadhaar, 4);
    }

    /** Mask a phone number, revealing only the last 4 digits (e.g. XXXXXX6789). */
    public static String maskPhone(String phone) {
        return maskAllButLast(phone, 4);
    }

    /** Mask the local part of an email, keeping the first char and the domain (e.g. j***@x.com). */
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return maskAllButLast(email, 0);
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String visible = local.substring(0, 1);
        return visible + MASK_CHAR.repeat(Math.max(1, local.length() - 1)) + domain;
    }

    /** Mask a bank account number, revealing only the last 4 digits. */
    public static String maskAccount(String account) {
        return maskAllButLast(account, 4);
    }

    private static String maskAllButLast(String value, int revealLast) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= revealLast) {
            return MASK_CHAR.repeat(trimmed.length());
        }
        String last = revealLast > 0 ? trimmed.substring(trimmed.length() - revealLast) : "";
        return MASK_CHAR.repeat(trimmed.length() - revealLast) + last;
    }
}
