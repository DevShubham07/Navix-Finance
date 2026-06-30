package com.navix.disbursement.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Pre-release gate for the <b>legacy</b> disbursement maker-checker chain ({@code /api/disbursement}):
 * blocks authorising a transfer unless a penny-drop confirms the beneficiary account exists AND the
 * name matches.
 *
 * <p>The <b>live</b> borrower/staff flow does its real penny-drop in
 * {@code ApplicationVerificationService} (Fintrix) and does not use this chain. This gate therefore
 * <b>fails closed by default</b> ({@code navix.disbursement.penny-drop-stub-pass=false}) so the stub can
 * never silently auto-approve a release in production. Set the flag to {@code true} only to run the
 * legacy chain end-to-end in a demo/test without live credentials.
 */
@Service
public class PennyDropGate {

    private final boolean stubPass;

    public PennyDropGate(@Value("${navix.disbursement.penny-drop-stub-pass:false}") boolean stubPass) {
        this.stubPass = stubPass;
    }

    /**
     * @return {@code true} only when the demo stub is explicitly enabled (legacy chain); otherwise
     *         <b>false</b> (fail closed). At go-live, replace with the navix-verification penny-drop result.
     */
    public boolean passed(UUID requestId) {
        return stubPass;
    }
}
