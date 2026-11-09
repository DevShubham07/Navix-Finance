package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoanMathDiscountTest {

    private final LoanMath math = new LoanMath();

    @Test
    void fivePercentOnTenThousandRupees() {
        assertThat(math.feeDiscountPaise(1_000_000L, 5)).isEqualTo(50_000L);
        assertThat(math.processingFeePaise(1_000_000L, 5)).isEqualTo(50_000L);
        assertThat(math.gstPaise(1_000_000L, 5)).isEqualTo(9_000L);
        assertThat(math.netDisbursedPaise(1_000_000L, 5)).isEqualTo(941_000L);
    }

    @Test
    void everyPercentFromOneToSevenLeavesAtLeastThreePercentFee() {
        for (int pct = 1; pct <= 7; pct++) {
            long fee = math.processingFeePaise(1_000_000L, pct);
            assertThat(fee).isEqualTo(100_000L - pct * 10_000L);
            assertThat(fee).isGreaterThanOrEqualTo(30_000L);
            assertThat(math.netDisbursedPaise(1_000_000L, pct))
                    .isEqualTo(1_000_000L - fee - math.gstPaise(1_000_000L, pct));
        }
    }

    @Test
    void zeroPercentAndLegacySignaturesAreUnchanged() {
        assertThat(math.feeDiscountPaise(1_000_000L, 0)).isZero();
        assertThat(math.processingFeePaise(1_000_000L, 0)).isEqualTo(math.processingFeePaise(1_000_000L));
        assertThat(math.gstPaise(1_000_000L, 0)).isEqualTo(18_000L);
        assertThat(math.netDisbursedPaise(1_000_000L, 0)).isEqualTo(882_000L);
    }

    @Test
    void roundingIsHalfUpOnOddPrincipals() {
        // 3 percent of 1_050 paise = 31.5 -> 32 ; base fee 105 -> 105 - 32 = 73 ; no negative fee
        assertThat(math.feeDiscountPaise(1_050L, 3)).isEqualTo(32L);
        assertThat(math.processingFeePaise(1_050L, 3)).isEqualTo(73L);
        assertThat(math.processingFeePaise(100L, 7)).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void interestAndPenaltyIgnoreTheDiscount() {
        assertThat(math.totalRepayablePaise(1_000_000L, 27)).isEqualTo(1_270_000L);
        assertThat(math.latePenaltyPaise(1_000_000L, 3)).isEqualTo(60_000L);
    }
}
