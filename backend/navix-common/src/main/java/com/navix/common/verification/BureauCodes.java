package com.navix.common.verification;

import java.util.Map;
import java.util.Optional;

/**
 * Code&rarr;label lookups for the numeric/letter codes embedded in an Experian bureau report
 * ({@code Account_Type}, {@code Portfolio_Type}, CAPS {@code Enquiry_Reason}, and the per-month
 * {@code Payment_History_Profile} bucket characters), plus an inferred {@code Account_Status} map.
 *
 * <p><b>Source.</b> Account type / portfolio type / enquiry reason / payment-history codes are
 * transcribed from the vendor spec
 * ({@code Digitap.ai Credit Analytics API Doc & Integration Guide_v2.8_16th_January_2026.pdf},
 * pages 61-66). {@code Account_Status} is <b>not</b> in that spec master at all — the mapping below
 * was inferred by profiling the actual status codes seen across 6,417 real production tradelines
 * (36 bureau responses) and cross-referencing them against each tradeline's {@code Date_Closed},
 * {@code Written_off_Settled_Status}, and DPD history. It is a best-effort inference, not a
 * vendor-documented contract — treat it as lower-confidence than the other three maps.
 *
 * <p><b>Every lookup returns {@link Optional#empty()} for a code this class does not recognise —
 * it never guesses.</b> A wrong label on a credit report a reviewer might sanction against is worse
 * than an honestly-unlabelled one ("Status 47" / "Type 63"); the caller is expected to render the
 * raw code in that case rather than fabricate a plausible-sounding name.
 */
public final class BureauCodes {

    private BureauCodes() {
    }

    private static final Map<String, String> ACCOUNT_TYPE = Map.ofEntries(
            Map.entry("1", "AUTO LOAN"),
            Map.entry("2", "HOUSING LOAN"),
            Map.entry("3", "PROPERTY LOAN"),
            Map.entry("4", "LOAN AGAINST SHARES/SECURITIES"),
            Map.entry("5", "PERSONAL LOAN"),
            Map.entry("6", "CONSUMER LOAN"),
            Map.entry("7", "GOLD LOAN"),
            Map.entry("8", "EDUCATIONAL LOAN"),
            Map.entry("9", "LOAN TO PROFESSIONAL"),
            Map.entry("10", "CREDIT CARD"),
            Map.entry("11", "LEASING"),
            Map.entry("12", "OVERDRAFT"),
            Map.entry("13", "TWO-WHEELER LOAN"),
            Map.entry("14", "NON-FUNDED CREDIT FACILITY"),
            Map.entry("15", "LOAN AGAINST BANK DEPOSITS"),
            Map.entry("16", "FLEET CARD"),
            Map.entry("17", "COMMERCIAL VEHICLE LOAN"),
            Map.entry("18", "TELCO - WIRELESS"),
            Map.entry("19", "TELCO - BROADBAND"),
            Map.entry("20", "TELCO - LANDLINE"),
            Map.entry("23", "GECL SECURED"),
            Map.entry("24", "GECL UNSECURED"),
            Map.entry("31", "SECURED CREDIT CARD"),
            Map.entry("32", "USED CAR LOAN"),
            Map.entry("33", "CONSTRUCTION EQUIPMENT LOAN"),
            Map.entry("34", "TRACTOR LOAN"),
            Map.entry("35", "CORPORATE CREDIT CARD"),
            Map.entry("36", "KISAN CREDIT CARD"),
            Map.entry("37", "LOAN ON CREDIT CARD"),
            Map.entry("38", "PMJDY OVERDRAFT"),
            Map.entry("39", "MUDRA LOANS"),
            Map.entry("40", "MICROFINANCE - BUSINESS LOAN"),
            Map.entry("41", "MICROFINANCE - PERSONAL LOAN"),
            Map.entry("42", "MICROFINANCE - HOUSING LOAN"),
            Map.entry("43", "MICROFINANCE - OTHERS"),
            Map.entry("44", "PMAY CLSS"),
            Map.entry("45", "P2P PERSONAL LOAN"),
            Map.entry("46", "P2P AUTO LOAN"),
            Map.entry("47", "P2P EDUCATION LOAN"),
            Map.entry("50", "BUSINESS LOAN - SECURED"),
            Map.entry("51", "BUSINESS LOAN - GENERAL"),
            Map.entry("52", "BUSINESS LOAN - PRIORITY SECTOR - SMALL BUSINESS"),
            Map.entry("53", "BUSINESS LOAN - PRIORITY SECTOR - AGRICULTURE"),
            Map.entry("54", "BUSINESS LOAN - PRIORITY SECTOR - OTHERS"),
            Map.entry("55", "BUSINESS NON-FUNDED CREDIT FACILITY-GENERAL"),
            Map.entry("56", "BUSINESS NON-FUNDED CREDIT FACILITY-PRIORITY SECTOR-SMALL BUSINESS"),
            Map.entry("57", "BUSINESS NON-FUNDED CREDIT FACILITY-PRIORITY SECTOR-AGRICULTURE"),
            Map.entry("58", "BUSINESS NON-FUNDED CREDIT FACILITY-PRIORITY SECTOR-OTHERS"),
            Map.entry("59", "BUSINESS LOANS AGAINST BANK DEPOSITS"),
            Map.entry("60", "STAFF LOAN"),
            Map.entry("61", "BUSINESS LOAN - UNSECURED"),
            Map.entry("69", "SHORT TERM PERSONAL LOAN [UNSECURED]"),
            Map.entry("70", "PRIORITY SECTOR GOLD LOAN [SECURED]"),
            Map.entry("71", "TEMPORARY OVERDRAFT [UNSECURED]"),
            Map.entry("0", "OTHERS"));

    private static final Map<String, String> PORTFOLIO_TYPE = Map.of(
            "F", "MICROFINANCE",
            "I", "INSTALMENT LOANS",
            "M", "MORTGAGE LOAN",
            "L", "OPEN LINES OF CREDIT",
            "R", "REVOLVING CREDIT",
            "S", "SINGLE PAYMENT LOANS");

    private static final Map<String, String> ENQUIRY_REASON = Map.ofEntries(
            Map.entry("1", "AGRICULTURE LOAN"),
            Map.entry("2", "AUTO LOAN"),
            Map.entry("3", "BUSINESS LOAN"),
            Map.entry("4", "COMMERCIAL VEHICLE LOANS"),
            Map.entry("5", "CONSTRUCTION EQUIPMENT LOAN"),
            Map.entry("6", "CONSUMER LOAN"),
            Map.entry("7", "CREDIT CARD"),
            Map.entry("8", "EDUCATION LOAN"),
            Map.entry("9", "LEASING"),
            Map.entry("10", "LOAN AGAINST COLLATERAL"),
            Map.entry("11", "MICROFINANCE"),
            Map.entry("12", "NON-FUNDED CREDIT FACILITY"),
            Map.entry("13", "PERSONAL LOAN"),
            Map.entry("14", "PROPERTY LOAN"),
            Map.entry("15", "TELECOM"),
            Map.entry("16", "TWO/THREE WHEELER LOAN"),
            Map.entry("17", "WORKING CAPITAL LOAN"),
            Map.entry("18", "CONSUMER LOAN"),
            Map.entry("19", "CREDIT REVIEW"),
            Map.entry("99", "OTHERS"));

    /**
     * Payment-history / asset-classification bucket characters. These are the individual characters
     * that make up a {@code Payment_History_Profile} string (one per reporting month) — see
     * {@code ExperianFactsParser} for how the 36-char / single-{@code N} string is walked.
     *
     * <p>{@code L} is deliberately mapped to "unknown" (not a spec entry — see the {@code Optional}
     * fallback) even though it is common in real data (180/149,010 characters observed): the vendor
     * spec master does not document it, and guessing what it means would violate the "never guess a
     * label" rule this class exists to enforce.
     */
    private static final Map<String, String> PAYMENT_HISTORY_BUCKET = Map.ofEntries(
            Map.entry("N", "NOT AVAILABLE"),
            Map.entry("?", "NOT AVAILABLE"),
            Map.entry("0", "0-29 DPD"),
            Map.entry("1", "30-59 DPD"),
            Map.entry("2", "60-89 DPD"),
            Map.entry("3", "90-119 DPD"),
            Map.entry("4", "120-149 DPD"),
            Map.entry("5", "150-179 DPD"),
            Map.entry("6", "180+ DPD"),
            Map.entry("S", "STANDARD"),
            Map.entry("B", "SUBSTANDARD"),
            Map.entry("D", "DOUBTFUL"),
            Map.entry("M", "SPECIAL MENTION ACCOUNT"));

    /**
     * {@code Account_Status} is not covered by the vendor spec master at all. This mapping is
     * <b>inferred</b> from profiling the 6,417 real production tradelines (cross-referenced against
     * {@code Date_Closed} / {@code Written_off_Settled_Status} / DPD history to sanity-check each
     * bucket) — it is not a documented vendor contract, so treat any code not listed here as
     * genuinely unknown rather than assuming it fits one of these buckets.
     */
    private static final Map<String, String> ACCOUNT_STATUS = Map.ofEntries(
            Map.entry("11", "ACTIVE"),
            Map.entry("21", "ACTIVE"),
            Map.entry("13", "CLOSED"),
            Map.entry("15", "CLOSED"),
            Map.entry("71", "DELINQUENT"),
            Map.entry("78", "DELINQUENT"),
            Map.entry("80", "DELINQUENT"),
            Map.entry("82", "DELINQUENT"),
            Map.entry("84", "DELINQUENT"),
            Map.entry("97", "DELINQUENT"),
            Map.entry("30", "RESTRUCTURED"),
            Map.entry("32", "SETTLED"),
            Map.entry("43", "WRITTEN-OFF"),
            Map.entry("45", "POST-WRITE-OFF SETTLED"));

    /** Account type (1-71 numeric, "0" = Others). Codes arrive zero-padded in real data
     *  ({@code "05"}, {@code "01"}, {@code "00"}) — normalised before lookup. */
    public static Optional<String> accountType(String code) {
        return lookup(ACCOUNT_TYPE, code);
    }

    /** Portfolio type (single-letter: F/I/M/L/R/S). */
    public static Optional<String> portfolioType(String code) {
        return lookup(PORTFOLIO_TYPE, code, false);
    }

    /** CAPS enquiry reason (1-19, 99). */
    public static Optional<String> enquiryReason(String code) {
        return lookup(ENQUIRY_REASON, code);
    }

    /** One {@code Payment_History_Profile} bucket character. */
    public static Optional<String> paymentHistoryBucket(String code) {
        return lookup(PAYMENT_HISTORY_BUCKET, code, false);
    }

    /** Account status — see the {@link #ACCOUNT_STATUS} javadoc for the inference caveat. */
    public static Optional<String> accountStatus(String code) {
        return lookup(ACCOUNT_STATUS, code);
    }

    /** Normalises a numeric code (strips leading zeros, e.g. {@code "05"} &rarr; {@code "5"}) then looks up. */
    private static Optional<String> lookup(Map<String, String> table, String code) {
        return lookup(table, code, true);
    }

    private static Optional<String> lookup(Map<String, String> table, String code, boolean normalizeNumeric) {
        if (code == null) {
            return Optional.empty();
        }
        String key = code.trim();
        if (key.isEmpty()) {
            return Optional.empty();
        }
        if (normalizeNumeric && key.chars().allMatch(Character::isDigit)) {
            // "05" -> "5", but keep a lone "0" as "0" (it is itself a valid code = Others).
            String stripped = key.replaceFirst("^0+(?=\\d)", "");
            key = stripped.isEmpty() ? "0" : stripped;
        }
        return Optional.ofNullable(table.get(key));
    }
}
