package com.navix.verification.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes CRIF's {@code COMBINED-PAYMENT-HISTORY} string — {@code "Aug:2026,000/XXX|Jul:2026,000/XXX|
 * …"}, one {@code Mon:YYYY,DPD/AssetClass} segment per reporting month, <b>newest first</b>. This is
 * NOT Experian's {@code Payment_History_Profile} bucket-character format ({@code ExperianFactsParser}'s
 * decoder) — the two must never be conflated. Package-private — only {@link CrifHighmarkFactsParser}
 * uses it today.
 */
final class PaymentHistory {

    private PaymentHistory() {
    }

    /**
     * @param month      the {@code Mon:YYYY} token, verbatim
     * @param dpdDays    days past due for that month, or {@code null} when the vendor reported
     *                   {@code "XXX"} (NOT REPORTED — a real unknown, not a clean {@code 0})
     * @param assetClass the asset-classification token ({@code STD}, {@code XXX}, …), verbatim
     */
    record MonthEntry(String month, Integer dpdDays, String assetClass) {
    }

    static List<MonthEntry> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<MonthEntry> out = new ArrayList<>();
        for (String segment : raw.split("\\|")) {
            if (segment.isBlank()) {
                continue;
            }
            int comma = segment.indexOf(',');
            if (comma < 0) {
                continue;
            }
            String month = segment.substring(0, comma).trim();
            String[] parts = segment.substring(comma + 1).trim().split("/", 2);
            String dpdRaw = parts.length > 0 ? parts[0].trim() : "";
            String assetClass = parts.length > 1 ? parts[1].trim() : null;
            out.add(new MonthEntry(month, parseDpd(dpdRaw), assetClass));
        }
        return out;
    }

    /** {@code "000"} &rarr; {@code 0}; {@code "027"} &rarr; {@code 27}; {@code "XXX"} &rarr; {@code null}. */
    private static Integer parseDpd(String dpdRaw) {
        if ("XXX".equalsIgnoreCase(dpdRaw)) {
            return null;
        }
        try {
            return Integer.valueOf(dpdRaw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Worst (highest) non-null DPD across the first {@code monthWindow} entries (newest-first, so this
     * is the trailing {@code monthWindow} reporting months). Pass {@link Integer#MAX_VALUE} for
     * all-time. Returns {@code null} — not {@code 0} — when every examined month was {@code "XXX"}
     * (not reported); a tradeline with no usable DPD data must not read as a clean 0-DPD record.
     */
    static Integer worstDpd(String raw, int monthWindow) {
        List<MonthEntry> months = decode(raw);
        Integer worst = null;
        int limit = Math.min(monthWindow, months.size());
        for (int i = 0; i < limit; i++) {
            Integer dpd = months.get(i).dpdDays();
            if (dpd != null && (worst == null || dpd > worst)) {
                worst = dpd;
            }
        }
        return worst;
    }
}
