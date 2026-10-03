package com.navix.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AadhaarTest {

    // 2345 6789 0124 is not a real UID; its check digit below is computed, not looked up.
    private static final String VALID = validNumber("23456789012");

    @Test
    void acceptsANumberWithAValidVerhoeffCheckDigit() {
        assertThat(Aadhaar.isValid(VALID)).isTrue();
    }

    @Test
    void rejectsASingleDigitTypoAndAnAdjacentTransposition() {
        char[] typo = VALID.toCharArray();
        typo[4] = typo[4] == '9' ? '0' : (char) (typo[4] + 1);
        assertThat(Aadhaar.isValid(new String(typo))).isFalse();

        char[] swapped = VALID.toCharArray();
        char t = swapped[5];
        swapped[5] = swapped[6];
        swapped[6] = t;
        if (swapped[5] != swapped[6]) {
            assertThat(Aadhaar.isValid(new String(swapped))).isFalse();
        }
    }

    @Test
    void rejectsTheWrongShape() {
        assertThat(Aadhaar.isValid(null)).isFalse();
        assertThat(Aadhaar.isValid("")).isFalse();
        assertThat(Aadhaar.isValid("1234567890")).isFalse();          // 10 digits
        assertThat(Aadhaar.isValid("1" + VALID.substring(1))).isFalse(); // leading 0/1 reserved
        assertThat(Aadhaar.isValid("0" + VALID.substring(1))).isFalse();
        assertThat(Aadhaar.isValid(VALID.substring(0, 11) + "x")).isFalse();
    }

    @Test
    void normalizeStripsTheCardSpacingOnly() {
        assertThat(Aadhaar.normalize(" 2345 6789 0124 ")).isEqualTo("234567890124");
        assertThat(Aadhaar.normalize("2345-6789-0124")).isEqualTo("234567890124");
        assertThat(Aadhaar.normalize("   ")).isNull();
        assertThat(Aadhaar.normalize(null)).isNull();
        // Letters are NOT stripped — a validator must see them and refuse.
        assertThat(Aadhaar.normalize("2345 6789 012a")).isEqualTo("23456789012a");
    }

    @Test
    void lastFourReadsAFullOrAMaskedNumber() {
        assertThat(Aadhaar.lastFour(VALID)).isEqualTo(VALID.substring(8));
        assertThat(Aadhaar.lastFour("XXXXXXXX1234")).isEqualTo("1234");
        assertThat(Aadhaar.lastFour("xxxx-xxxx-5678")).isEqualTo("5678");
        assertThat(Aadhaar.lastFour("XXXX")).isNull();
        assertThat(Aadhaar.lastFour(null)).isNull();
    }

    @Test
    void matchesMaskedComparesWhateverDigitsTheMaskReveals() {
        String n = "234567890124";
        assertThat(Aadhaar.matchesMasked(n, "XXXXXXXX0124")).isEqualTo(Aadhaar.MaskMatch.MATCH);
        assertThat(Aadhaar.matchesMasked(n, "xxxx xxxx 0124")).isEqualTo(Aadhaar.MaskMatch.MATCH);
        // Signzy-style PAN-record mask: first two + last two visible.
        assertThat(Aadhaar.matchesMasked(n, "23XXXXXXXX24")).isEqualTo(Aadhaar.MaskMatch.MATCH);
        assertThat(Aadhaar.matchesMasked(n, "65XXXXXXXX90")).isEqualTo(Aadhaar.MaskMatch.MISMATCH);
        assertThat(Aadhaar.matchesMasked(n, "XXXXXXXX9999")).isEqualTo(Aadhaar.MaskMatch.MISMATCH);
        // A short mask falls back to its trailing digits.
        assertThat(Aadhaar.matchesMasked(n, "***0124")).isEqualTo(Aadhaar.MaskMatch.MATCH);
        assertThat(Aadhaar.matchesMasked(n, "***9999")).isEqualTo(Aadhaar.MaskMatch.MISMATCH);
        // Nothing to compare is never a mismatch.
        assertThat(Aadhaar.matchesMasked(n, null)).isEqualTo(Aadhaar.MaskMatch.UNKNOWN);
        assertThat(Aadhaar.matchesMasked(n, "XXXXXXXXXXXX")).isEqualTo(Aadhaar.MaskMatch.UNKNOWN);
        assertThat(Aadhaar.matchesMasked(null, "XXXXXXXX0124")).isEqualTo(Aadhaar.MaskMatch.UNKNOWN);
        assertThat(Aadhaar.matchesMasked("", "XXXXXXXX0124")).isEqualTo(Aadhaar.MaskMatch.UNKNOWN);
    }

    /** Appends the Verhoeff check digit to an 11-digit body by trying each candidate. */
    private static String validNumber(String body) {
        for (char d = '0'; d <= '9'; d++) {
            String candidate = body + d;
            if (Aadhaar.isValid(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("no check digit completes " + body);
    }
}
