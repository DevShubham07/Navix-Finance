package com.navix.notification.template;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Small formatting helpers so notification model values are display-ready strings before
 * substitution. Money is integer paise → {@code ₹}-prefixed rupees; dates → {@code dd MMM yyyy}.
 */
public final class NotificationFormat {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private NotificationFormat() {
    }

    /** Integer paise → e.g. {@code ₹8,820}. Null → {@code —}. */
    public static String inr(Long paise) {
        if (paise == null) {
            return "—";
        }
        return "₹" + String.format(Locale.ENGLISH, "%,d", paise / 100);
    }

    /** {@link LocalDate} → e.g. {@code 30 Jun 2026}. Null → {@code —}. */
    public static String date(LocalDate d) {
        return d == null ? "—" : DATE.format(d);
    }
}
