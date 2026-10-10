package com.navix.common.util;

/** Mobile-number normalisation shared by the registered-mobile and bureau-mobile paths. */
public final class Mobiles {

    private Mobiles() {}

    /** Digits only, last 10 ("+91 98765 43210" and "09876543210" both become the 10-digit form); null when blank or digit-free. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }
}
