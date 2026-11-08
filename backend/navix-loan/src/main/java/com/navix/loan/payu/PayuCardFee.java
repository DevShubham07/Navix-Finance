package com.navix.loan.payu;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The card fee added on top of a repayment through {@code additional_charges}, so the loan is credited the
 * full amount. PayU takes {@code bps} of the TOTAL charged, plus GST on that, so the grossed-up fee is
 * {@code ceil(base * n / (100_000_000 - n))} with {@code n = bps * (10_000 + gstBps)}. Integer maths only.
 */
public final class PayuCardFee {

    private final Map<String, Integer> bpsByMode = new LinkedHashMap<>();
    private final int gstBps;

    public PayuCardFee(String config, int gstBps) {
        this.gstBps = gstBps;
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

    static long fee(long basePaise, int bps, int gstBps) {
        long n = (long) bps * (10_000L + gstBps);
        long den = 100_000_000L - n;
        if (n <= 0 || den <= 0) {
            return 0;
        }
        return Math.floorDiv(basePaise * n + den - 1, den); // ceil
    }

    /** The largest per-mode fee for {@code basePaise}, for the "card fee shown at checkout" note; null when none. */
    public Long displayFeePaise(long basePaise) {
        return bpsByMode.values().stream().map(b -> fee(basePaise, b, gstBps)).max(Long::compare).orElse(null);
    }

    /** {@code CC:241.71,AMEX:241.71,...,DC:0,NB:0}; null when no fee is configured (the param is then not sent). */
    public String additionalCharges(long basePaise) {
        if (!configured()) {
            return null;
        }
        StringBuilder s = new StringBuilder();
        bpsByMode.forEach((mode, bps) ->
                s.append(mode).append(':').append(BigDecimal.valueOf(fee(basePaise, bps, gstBps), 2)).append(','));
        return s.append("DC:0,NB:0").toString();
    }
}
