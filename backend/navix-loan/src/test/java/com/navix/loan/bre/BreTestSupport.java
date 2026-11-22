package com.navix.loan.bre;

import static org.mockito.Mockito.mock;

import com.navix.loan.bre.BreRuleCatalog.Rule;

/** A BreRuleService mock that behaves like an untouched database: catalog defaults everywhere. */
public final class BreTestSupport {

    private BreTestSupport() {
    }

    /**
     * Every rule on or off exactly as in production with no feature_flag row, except the bureau rule,
     * which the existing tests exercise and so default ON here. Override with {@code when(...)}.
     */
    public static BreRuleService defaults() {
        return mock(BreRuleService.class, inv -> {
            String name = inv.getMethod().getName();
            String key = inv.getArguments().length > 0 ? String.valueOf(inv.getArguments()[0]) : null;
            if (name.equals("isOn")) {
                Rule r = BreRuleCatalog.BY_KEY.get(key);
                return r.key().equals(BreRuleCatalog.LOW_BUREAU_SCORE) || r.defaultOn();
            }
            if (name.equals("threshold")) {
                return (int) BreRuleCatalog.THRESHOLDS.get(key).defaultValue();
            }
            return null;
        });
    }
}
