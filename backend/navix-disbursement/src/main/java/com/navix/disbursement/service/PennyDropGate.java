package com.navix.disbursement.service;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Pre-release gate: blocks authorising a transfer unless a penny-drop verification
 * confirms the beneficiary account exists AND the account holder name matches.
 * Delegates to navix-verification (Fintrix-backed penny drop).
 *
 * Stub for scaffolding.
 */
@Service
public class PennyDropGate {

    // TODO: inject navix-verification client/service.

    /**
     * @return true only when account_exists && name match; otherwise release must be blocked.
     * TODO: call navix-verification penny-drop and evaluate the result.
     */
    public boolean isReleaseAllowed(UUID loanId) {
        throw new UnsupportedOperationException("TODO: implement penny-drop gate via navix-verification");
    }
}
