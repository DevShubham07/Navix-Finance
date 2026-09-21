package com.navix.app.search;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the operator typed, normalised once.
 *
 * <p>Ops paste whatever they have: a mobile with or without {@code +91}, a PAN in whatever case the
 * source document used, an application id with or without a leading {@code #}. Normalising here — in
 * one pure, testable place — is what lets every group downstream receive a single {@code needle} and
 * stay ignorant of the input's shape. The frontend mirrors this in
 * {@code lib/staff/global-search.ts} purely to label the hint chip before the response lands; this
 * class is the authority.
 *
 * <p>A digit string is either a mobile or an id, never both: a 10-digit Indian mobile starts 6-9,
 * anything shorter is an id. Trying both would double every query to answer a question the shape
 * already settles.
 */
public record SearchQuery(String raw, String needle, Kind kind) {

    public enum Kind {
        MOBILE, PAN, ID, TEXT;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Pattern MOBILE = Pattern.compile("^(?:\\+?91)?([6-9]\\d{9})$");
    private static final Pattern PAN = Pattern.compile("^[A-Za-z]{5}\\d{4}[A-Za-z]$");
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    /** Minimum useful length: a single letter matches most of the book, a single digit does not. */
    private static final int MIN_TEXT_LENGTH = 2;

    public static SearchQuery parse(String raw) {
        String cleaned = raw == null ? "" : raw.trim().replaceFirst("^#", "").replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return new SearchQuery(cleaned, "", Kind.TEXT);
        }

        String compact = cleaned.replaceAll("[^0-9]", "");
        Matcher mobile = MOBILE.matcher(cleaned);
        if (mobile.matches()) {
            return new SearchQuery(cleaned, mobile.group(1), Kind.MOBILE);
        }
        Matcher compactMobile = MOBILE.matcher(compact);
        if (compactMobile.matches()) {
            return new SearchQuery(cleaned, compactMobile.group(1), Kind.MOBILE);
        }
        if (PAN.matcher(cleaned).matches()) {
            return new SearchQuery(cleaned, cleaned.toUpperCase(Locale.ROOT), Kind.PAN);
        }
        if (DIGITS.matcher(cleaned).matches()) {
            return new SearchQuery(cleaned, cleaned, Kind.ID);
        }
        return new SearchQuery(cleaned, cleaned, Kind.TEXT);
    }

    /** Worth running? Text needs two characters; a single digit is already a usable id prefix. */
    public boolean searchable() {
        if (needle.isEmpty()) {
            return false;
        }
        return kind == Kind.TEXT ? needle.length() >= MIN_TEXT_LENGTH : true;
    }

    public boolean isNumericId() {
        return kind == Kind.ID;
    }

    public String interpretedAs() {
        return kind.wire();
    }
}
