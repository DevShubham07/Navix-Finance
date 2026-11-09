package com.navix.loan.service;

import java.util.Map;
import java.util.Set;

/**
 * Read-only public view of {@link CustomerService#HANDOVER_DERIVED_KEYS} — the whitelist of
 * {@code application_verification.derived} keys that may leave the company — so the admin customer
 * export reuses the one list instead of keeping a second copy that can drift.
 */
public final class HandoverDerivedKeys {

    private HandoverDerivedKeys() {
    }

    public static Map<String, Set<String>> byCheckType() {
        return CustomerService.HANDOVER_DERIVED_KEYS;
    }
}
