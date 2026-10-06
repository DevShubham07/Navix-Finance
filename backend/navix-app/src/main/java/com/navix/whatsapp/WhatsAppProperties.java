package com.navix.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code navix.whatsapp.*} — the SmartChat WhatsApp Business API (docs/whatsapp/WHATSAPP_GUIDE.md).
 * {@code token} is the SmartChat API token (SSM {@code /navix/<env>/navix/whatsapp/token}); a blank
 * token or {@code enabled=false} turns every send into a no-op failure, never a crash.
 */
@ConfigurationProperties(prefix = "navix.whatsapp")
public record WhatsAppProperties(
        String baseUrl,
        String token,
        boolean enabled,
        /** Demo/testing: return a mock id, no HTTP call. Mirrors {@code navix.sms.mock}. */
        boolean mock,
        /** AUTHENTICATION template carrying the login OTP. */
        String otpTemplate
) {
    public boolean live() {
        return enabled && token != null && !token.isBlank();
    }
}
