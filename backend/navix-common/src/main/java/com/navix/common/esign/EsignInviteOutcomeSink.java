package com.navix.common.esign;

/**
 * Where the notification module reports how an eSign invite email went. Implemented in navix-loan
 * (which owns the signer rows); lives here because navix-notification cannot depend on navix-loan.
 */
public interface EsignInviteOutcomeSink {

    /** @param status SENT | FAILED | SKIPPED */
    void recordInviteOutcome(Long signerRowId, String status);
}
