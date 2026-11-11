package com.navix.common.notification.event;

/**
 * Admin "eSign documents": ask an external signer, by email, to sign. Published once per emailed
 * signer; the notification module sends it and reports the outcome through
 * {@code EsignInviteOutcomeSink}. Carries the signer's hosted signing link, so it is never logged.
 */
public record EsignInviteEvent(Long signerRowId, String documentTitle, String signerName,
                               String email, String signUrl) {
}
