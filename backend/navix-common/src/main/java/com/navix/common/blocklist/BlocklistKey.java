package com.navix.common.blocklist;

import java.util.Locale;

/**
 * One identifier as the fraud blocklist stores it: a {@code BlocklistType} name ({@code PHONE},
 * {@code PAN}, {@code AADHAAR_REF}, {@code BANK_ACCOUNT}, {@code DEVICE}) and its <b>normalised</b>
 * value. Both the blocklist page and every screening check go through {@link #of}, so "abcde1234f",
 * "+91 98765 43210" and "9876543210" match what an ADMIN typed however they typed it.
 */
public record BlocklistKey(String type, String value) {

    /** The normalised key, or null when there is nothing to match on (blank / no digits). */
    public static BlocklistKey of(String type, String raw) {
        String value = normalize(type, raw);
        return type == null || value == null ? null : new BlocklistKey(type, value);
    }

    /**
     * PHONE → the last 10 digits (drops +91 / a leading 0); PAN → uppercase, no spaces;
     * AADHAAR_REF and BANK_ACCOUNT → digits only; anything else → trimmed. Blank → null.
     */
    public static String normalize(String type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = switch (type == null ? "" : type) {
            case "PHONE" -> {
                String d = raw.replaceAll("\\D", "");
                yield d.length() > 10 ? d.substring(d.length() - 10) : d;
            }
            case "PAN" -> raw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
            case "AADHAAR_REF", "BANK_ACCOUNT" -> raw.replaceAll("\\D", "");
            default -> raw.trim();
        };
        return v.isEmpty() ? null : v;
    }
}
