package com.navix.loan.payu;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The card fee added on top of a repayment through {@code additional_charges}: a flat percentage of the
 * repayment, rounded up to the paisa. Integer maths only.
 *
 * <p>ponytail: flat percentage by product decision, so PayU's charge on the fee itself is not recovered
 * (about Rs 5.57 per Rs 10,000 at 2.36%). Gross up here if the lender must net the full repayment.
 */
public final class PayuCardFee {

    private final Map<String, Integer> bpsByMode = new LinkedHashMap<>();

    public PayuCardFee(String config) {
        if (config != null) {
            for (String part : config.split(",")) {
                String[] kv = part.trim().split(":");
                if (kv.length == 2 && !kv[0].isBlank()) {
                    bpsByMode.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
                }
            }
        }
    }

    public boolean configured() {
        return !bpsByMode.isEmpty();
    }

    static long fee(long basePaise, int bps) {
        return bps <= 0 ? 0 : Math.floorDiv(basePaise * bps + 9_999, 10_000L); // ceil
    }

    /** The largest per-mode fee for {@code basePaise}, for the "card fee shown at checkout" note; null when none. */
    public Long displayFeePaise(long basePaise) {
        return bpsByMode.values().stream().map(b -> fee(basePaise, b)).max(Long::compare).orElse(null);
    }

    /** {@code CC:236.00,AMEX:236.00,...,DC:0,NB:0}; null when no fee is configured (the param is then not sent). */
    public String additionalCharges(long basePaise) {
        if (!configured()) {
            return null;
        }
        StringBuilder s = new StringBuilder();
        bpsByMode.forEach((mode, bps) ->
                s.append(mode).append(':').append(BigDecimal.valueOf(fee(basePaise, bps), 2)).append(','));
        return s.append("DC:0,NB:0").toString();
    }
}
