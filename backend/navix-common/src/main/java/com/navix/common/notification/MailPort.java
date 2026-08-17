package com.navix.common.notification;

/**
 * Minimal outbound-email port for modules that must send a real email but must not depend on
 * {@code navix-notification} internals (e.g. {@code navix-loan}'s DSA lead outreach — a lead is
 * not a customer/staff recipient, so the notification catalog/dispatcher is not a fit; this is a
 * direct send, like {@code ContactService}/{@code PasswordResetService}). Implemented by an
 * adapter in {@code navix-notification} that delegates to the existing {@code EmailClient} +
 * {@code EmailHtmlRenderer}, wired by component scan — the same "swap at a seam" pattern as
 * {@link com.navix.common.loan.LoanDirectory} / {@link com.navix.common.staff.StaffDirectory}.
 */
public interface MailPort {

    /**
     * Send a branded email with {@code textBody} wrapped in the standard DhanBoost HTML shell.
     *
     * @return {@code true} if the send succeeded (or the provider is the no-op {@code log} client
     *         in a state that counts as success), {@code false} on a real delivery failure
     */
    boolean send(String to, String subject, String textBody);
}
