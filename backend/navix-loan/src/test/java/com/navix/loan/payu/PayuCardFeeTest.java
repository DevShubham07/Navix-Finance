package com.navix.loan.payu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PayuCardFeeTest {

    @Test
    void grossedUpFee_forTenThousandRupees_is24171Paise() {
        assertThat(PayuCardFee.fee(1_000_000, 200, 1800)).isEqualTo(24_171);
    }

    @Test
    void feeRoundsUp() {
        // 100 paise: 100 * 2_360_000 / 97_640_000 = 2.417 -> 3
        assertThat(PayuCardFee.fee(100, 200, 1800)).isEqualTo(3);
        assertThat(PayuCardFee.fee(1, 200, 1800)).isEqualTo(1);
    }

    @Test
    void additionalCharges_hasTheShapeTheSandboxAccepted() {
        PayuCardFee f = new PayuCardFee("CC:200,AMEX:200,RUPAYCC:200,DINR:200", 1800);
        assertThat(f.additionalCharges(1_000_000))
                .isEqualTo("CC:241.71,AMEX:241.71,RUPAYCC:241.71,DINR:241.71,DC:0,NB:0");
        assertThat(f.displayFeePaise(1_000_000)).isEqualTo(24_171L);
    }

    @Test
    void blankConfig_sendsNoChargesAtAll() {
        for (String cfg : new String[] {null, "", "  "}) {
            PayuCardFee f = new PayuCardFee(cfg, 1800);
            assertThat(f.configured()).isFalse();
            assertThat(f.additionalCharges(1_000_000)).isNull();
            assertThat(f.displayFeePaise(1_000_000)).isNull();
        }
    }
}
