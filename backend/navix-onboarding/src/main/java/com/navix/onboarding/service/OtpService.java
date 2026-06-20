package com.navix.onboarding.service;

import org.springframework.stereotype.Service;

/**
 * NAVIX's own OTP service (send + verify, with resend).
 * STUB: no real SMS/email provider wired yet.
 * TODO: generate and store time-bound OTPs, enforce resend/throttle limits,
 *       and integrate the actual delivery channel.
 */
@Service
public class OtpService {

    /** Send an OTP to the given destination (mobile or email). */
    public void send(String destination) {
        // TODO: generate OTP, persist with expiry, dispatch via provider.
        throw new UnsupportedOperationException("OtpService.send not implemented");
    }

    /** Resend the most recent OTP, respecting cooldown/attempt limits. */
    public void resend(String destination) {
        // TODO: reissue OTP subject to throttle rules.
        throw new UnsupportedOperationException("OtpService.resend not implemented");
    }

    /** Verify the supplied code against the active OTP for the destination. */
    public boolean verify(String destination, String code) {
        // TODO: validate code, expiry and attempt count.
        throw new UnsupportedOperationException("OtpService.verify not implemented");
    }
}
