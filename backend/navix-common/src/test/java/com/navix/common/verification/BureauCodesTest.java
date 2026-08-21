package com.navix.common.verification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BureauCodesTest {

    @Test
    void knownAccountTypeCodesResolve() {
        assertThat(BureauCodes.accountType("10")).contains("CREDIT CARD");
        assertThat(BureauCodes.accountType("1")).contains("AUTO LOAN");
        assertThat(BureauCodes.accountType("0")).contains("OTHERS");
    }

    @Test
    void unknownAccountTypeCodeReturnsEmptyNotAGuess() {
        assertThat(BureauCodes.accountType("999")).isEmpty();
        assertThat(BureauCodes.accountType(null)).isEmpty();
        assertThat(BureauCodes.accountType("")).isEmpty();
    }

    @Test
    void zeroPaddedNumericCodesNormalizeBeforeLookup() {
        assertThat(BureauCodes.accountType("05")).isEqualTo(BureauCodes.accountType("5"));
        assertThat(BureauCodes.accountType("00")).contains("OTHERS");
    }

    @Test
    void portfolioTypeCodesResolve() {
        assertThat(BureauCodes.portfolioType("R")).contains("REVOLVING CREDIT");
        assertThat(BureauCodes.portfolioType("Z")).isEmpty();
    }

    @Test
    void enquiryReasonCodesResolve() {
        assertThat(BureauCodes.enquiryReason("7")).contains("CREDIT CARD");
        assertThat(BureauCodes.enquiryReason("99")).contains("OTHERS");
        assertThat(BureauCodes.enquiryReason("42")).isEmpty();
    }

    @Test
    void paymentHistoryBucketCodesResolve() {
        assertThat(BureauCodes.paymentHistoryBucket("0")).contains("0-29 DPD");
        assertThat(BureauCodes.paymentHistoryBucket("S")).contains("STANDARD");
        assertThat(BureauCodes.paymentHistoryBucket("?")).contains("NOT AVAILABLE");
        // Undocumented in the vendor spec — must not guess.
        assertThat(BureauCodes.paymentHistoryBucket("L")).isEmpty();
    }

    @Test
    void accountStatusCodesResolveFromTheInferredMap() {
        assertThat(BureauCodes.accountStatus("11")).contains("ACTIVE");
        assertThat(BureauCodes.accountStatus("13")).contains("CLOSED");
        assertThat(BureauCodes.accountStatus("43")).contains("WRITTEN-OFF");
        assertThat(BureauCodes.accountStatus("64")).isEmpty();
    }
}
