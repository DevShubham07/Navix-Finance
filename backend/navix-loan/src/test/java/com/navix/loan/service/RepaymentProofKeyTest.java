package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RepaymentProofKeyTest {

    @Test
    void onlyARealRepaymentProofKeyIsPresigned() {
        assertThat(RepaymentService.isProofKey("loan/repayment-proof/abc-shot.png")).isTrue();
        for (String bad : new String[] {null, "", "  ", "NACH:UTR123", "UTR 4567 paid by cash",
                "applications/7/aadhaar_card_front/1.jpg", "loan/repayment-proof/../applications/7/pan.jpg"}) {
            assertThat(RepaymentService.isProofKey(bad)).as(String.valueOf(bad)).isFalse();
        }
    }
}
