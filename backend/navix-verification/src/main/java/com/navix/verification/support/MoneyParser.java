package com.navix.verification.support;

/**
 * Parses CRIF's Indian-grouped money strings ({@code "10,00,000"}, {@code "1,12,685"}) into rupees.
 * Package-private — only {@link CrifHighmarkFactsParser} uses it today.
 */
final class MoneyParser {

    private MoneyParser() {
    }

    /**
     * {@code "10,00,000"} &rarr; {@code 1000000L}; {@code ""}/{@code null} &rarr; {@code null};
     * {@code "0"} &rarr; {@code 0L}. Any fractional part (CRIF's {@code OBLIGATION} field carries
     * sub-paise decimals, e.g. {@code "21001.861309715707"}) is <b>truncated, not rounded</b> — this
     * value is display-only and only needs whole-rupee precision.
     */
    static Long indianRupees(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace(",", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return (long) Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
