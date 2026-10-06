package com.navix.common.util;

/**
 * Aadhaar number shape + checksum validation (UIDAI Verhoeff).
 *
 * <p>An Aadhaar number is twelve digits whose first digit is 2–9 (0 and 1 are reserved) and whose
 * last digit is a Verhoeff check digit over the first eleven. The check catches every single-digit
 * typo and every adjacent transposition, which is exactly the class of error a borrower typing the
 * number from the card makes — so the intake screen can refuse a mistyped number before it is
 * stored and before it is compared against the provider's masked copy by the fraud rule.
 *
 * <p>Pure functions, no I/O. The frontend carries an identical implementation in
 * {@code lib/aadhaar.ts}; keep the two in step.
 */
public final class Aadhaar {

    private Aadhaar() {
    }

    // Verhoeff tables: multiplication (d), permutation (p), inverse (inv).
    private static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
            {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
            {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
            {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
            {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
            {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0},
    };
    private static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
            {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
            {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
            {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
            {7, 0, 4, 6, 9, 1, 3, 2, 5, 8},
    };

    /**
     * Digits only, with spaces/hyphens stripped (cards print the number as {@code 1234 5678 9012}).
     * Returns {@code null} for a null/blank input; does <b>not</b> validate — see {@link #isValid}.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[\\s-]", "");
        return digits.isEmpty() ? null : digits;
    }

    /** True iff {@code aadhaar} (already {@link #normalize normalized}) is 12 digits, starts 2–9 and
     *  carries a valid Verhoeff check digit. */
    public static boolean isValid(String aadhaar) {
        if (aadhaar == null || aadhaar.length() != 12) {
            return false;
        }
        char first = aadhaar.charAt(0);
        if (first < '2' || first > '9') {
            return false;
        }
        int c = 0;
        int len = aadhaar.length();
        for (int i = 0; i < len; i++) {
            char ch = aadhaar.charAt(len - 1 - i);
            if (ch < '0' || ch > '9') {
                return false;
            }
            c = D[c][P[i % 8][ch - '0']];
        }
        return c == 0;
    }

    /** Outcome of {@link #matchesMasked}. */
    public enum MaskMatch { MATCH, MISMATCH, UNKNOWN }

    /**
     * Does a full 12-digit number agree with a provider's <em>masked</em> copy of it?
     *
     * <p>Providers mask differently — Fintrix/DigiLocker return {@code XXXXXXXX1234}, Signzy's PAN
     * record has been seen as {@code 65XXXXXXXX90} (first two and last two visible) — so this is a
     * positional comparison: after stripping card spacing, a 12-character mask is compared digit by
     * digit wherever it shows one. A mask of another length falls back to its trailing digit run.
     * {@link MaskMatch#UNKNOWN} when either side is missing or the mask reveals nothing, so a caller
     * never treats "nothing to compare" as a disagreement.
     */
    public static MaskMatch matchesMasked(String full, String masked) {
        String number = normalize(full);
        String mask = normalize(masked);
        if (number == null || number.length() != 12 || mask == null) {
            return MaskMatch.UNKNOWN;
        }
        if (mask.length() == 12) {
            boolean compared = false;
            for (int i = 0; i < 12; i++) {
                char m = mask.charAt(i);
                if (m < '0' || m > '9') {
                    continue;
                }
                compared = true;
                if (m != number.charAt(i)) {
                    return MaskMatch.MISMATCH;
                }
            }
            return compared ? MaskMatch.MATCH : MaskMatch.UNKNOWN;
        }
        String tail = lastFour(mask);
        if (tail == null) {
            return MaskMatch.UNKNOWN;
        }
        return number.endsWith(tail) ? MaskMatch.MATCH : MaskMatch.MISMATCH;
    }

    /** The last four digits, or {@code null} when fewer than four digits are present. Accepts the
     *  provider's masked form ({@code XXXXXXXX1234}, {@code xxxx-xxxx-1234}) as well as a full number. */
    public static String lastFour(String aadhaarOrMasked) {
        if (aadhaarOrMasked == null) {
            return null;
        }
        String digits = aadhaarOrMasked.replaceAll("\\D", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : null;
    }
}
