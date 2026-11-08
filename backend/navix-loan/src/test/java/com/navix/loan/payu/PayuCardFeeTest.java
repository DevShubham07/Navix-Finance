package com.navix.loan.payu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PayuCardFeeTest {

    @Test
    void fee_isAFlatPercentageOfTheRepayment() {
        assertThat(PayuCardFee.fee(1_000_000, 236)).isEqualTo(23_600); // 2.36% of Rs 10,000
    }

    @Test
    void feeRoundsUpToThePaisa() {
        assertThat(PayuCardFee.fee(100, 236)).isEqualTo(3); // 2.36 -> 3
        assertThat(PayuCardFee.fee(1, 236)).isEqualTo(1);
        assertThat(PayuCardFee.fee(10_000, 236)).isEqualTo(236); // exact: not bumped
    }

    @Test
    void additionalCharges_hasTheShapeTheSandboxAccepted() {
        PayuCardFee f = new PayuCardFee("CC:236,AMEX:236,RUPAYCC:236,DINR:236");
        assertThat(f.additionalCharges(1_000_000))
                .isEqualTo("CC:236.00,AMEX:236.00,RUPAYCC:236.00,DINR:236.00,DC:0,NB:0");
        assertThat(f.displayFeePaise(1_000_000)).isEqualTo(23_600L);
    }

    @Test
    void blankConfig_sendsNoChargesAtAll() {
        for (String cfg : new String[] {null, "", "  "}) {
            PayuCardFee f = new PayuCardFee(cfg);
            assertThat(f.configured()).isFalse();
            assertThat(f.additionalCharges(1_000_000)).isNull();
            assertThat(f.displayFeePaise(1_000_000)).isNull();
        }
    }
}
