package com.navix.loan.payu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Expected values were computed independently (Python hashlib.sha512) from the formulas in docs/payu/README.md. */
class PayuHashTest {

    private static final String ANSWER_PLAIN =
            "caf9ffbc6688eac39dbbfd4e376361997acec69b8945a05216ae37d2d065811abc02517d786008988f2878eab8102a9bbabe16079395ccfbccf83edfd4ce8931";
    private static final String ANSWER_CHARGES =
            "3db5ca85c6201653fa58454c755faf30711a8da81d76ca16eb5189c69c893b09f0f531f091a0068321c8fc392eace1c4f4c5f33068d25299778e3be521ee9391";

    @Test
    void request_matchesTheFormula() {
        assertThat(PayuHash.request("K", "DB1", "10.00", "DhanBoost loan repayment", "Asha", "a@b.in",
                "7", "", "", "", "", "S", null)).isEqualTo(ANSWER_PLAIN);
        assertThat(PayuHash.request("K", "DB1", "10.00", "DhanBoost loan repayment", "Asha", "a@b.in",
                "7", null, null, null, null, "S", "")).isEqualTo(ANSWER_PLAIN);
    }

    @Test
    void request_appendsAdditionalChargesAfterTheSalt() {
        assertThat(PayuHash.request("K", "DB1", "10.00", "DhanBoost loan repayment", "Asha", "a@b.in",
                "7", "", "", "", "", "S", "CC:0.24,DC:0,NB:0")).isEqualTo(ANSWER_CHARGES);
    }

    private static Map<String, String> response() {
        Map<String, String> f = new HashMap<>();
        f.put("status", "success");
        f.put("udf1", "7");
        f.put("email", "a@b.in");
        f.put("firstname", "Asha");
        f.put("productinfo", "DhanBoost loan repayment");
        f.put("amount", "10.00");
        f.put("txnid", "DB1");
        f.put("key", "K");
        return f;
    }

    @Test
    void reverse_withoutAdditionalCharges() {
        assertThat(PayuHash.reverse(response(), "S")).isEqualTo(
                "e7edb364a30adcad11d7e1baf1b8ca9750ff6ecb5cc1463c4dea5af0d80935c1e3185e6e79cdc7866a252e130e5b6f409daa44782807be33c85d67464f9f6800");
    }

    @Test
    void reverse_withAdditionalChargesPrefixesThem() {
        Map<String, String> f = response();
        f.put("additionalCharges", "0.24");
        assertThat(PayuHash.reverse(f, "S")).isEqualTo(
                "39560ed6a1aa1ccd61ba7e49936f34e209a99a3fe4d61c5d818b2010465a31dfdb336da19fa18eedcd90752b2ee678d483e7eaf8796d9d0d87fd9143f12d8b44");
        f.put("additionalCharges", " ");
        assertThat(PayuHash.reverse(f, "S")).isEqualTo(PayuHash.reverse(response(), "S"));
    }

    @Test
    void command_matchesTheFormula() {
        assertThat(PayuHash.command("K", "verify_payment", "DB1", "S")).isEqualTo(
                "d52a5c1ffac264d6e151e22a852ef73538a879360f6824f0d1a71cc722a5c3278b873128818d086e1e4f0903f17d355f3a057bd2de760a39e899a9f780354f72");
    }

    @Test
    void matches_isExactAndNullSafe() {
        String h = PayuHash.command("K", "verify_payment", "DB1", "S");
        assertThat(PayuHash.matches(h, h.toUpperCase())).isTrue();
        assertThat(PayuHash.matches(h, h.substring(1))).isFalse();
        assertThat(PayuHash.matches(h, null)).isFalse();
    }
}
