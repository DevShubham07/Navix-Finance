package com.navix.disbursement.service;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Pre-release gate: blocks authorising a transfer unless a penny-drop verification confirms the
 * beneficiary account exists AND the account-holder name matches. At go-live this delegates to
 * navix-verification (Fintrix-backed penny drop) behind a Spring profile.
 *
 * <p><b>Demo mode:</b> returns {@code true} (bank-account verification stub) so the maker-checker
 * chain runs end-to-end without live credentials.
 */
@Service
public class PennyDropGate {

    // TODO (go-live): inject the navix-verification penny-drop client and evaluate the real result.

    /**
     * @return {@code true} only when {@code account_exists && name match}; otherwise release must be
     *         blocked. Demo stub always passes.
     */
    public boolean passed(UUID requestId) {
        return true;
    }
}
