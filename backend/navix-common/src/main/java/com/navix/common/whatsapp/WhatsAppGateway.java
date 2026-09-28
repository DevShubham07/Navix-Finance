package com.navix.common.whatsapp;

import java.util.List;

/**
 * Outbound WhatsApp port (SmartChat API). WhatsApp only delivers pre-approved Meta templates outside a
 * 24h reply window, so a send is a template name plus its ordered {@code {{1}}..{{n}}} values — the
 * wording lives on the provider side. Implemented in navix-app; returns the provider message id and
 * may throw on failure (the notification {@code WhatsAppSender} isolates that).
 */
public interface WhatsAppGateway {

    /** Send approved template {@code templateName} to {@code number} ({@code 91XXXXXXXXXX}). */
    String sendTemplate(String number, String templateName, List<String> params);

    /** Send the login OTP through the AUTHENTICATION template. */
    String sendOtp(String number, String code);
}
