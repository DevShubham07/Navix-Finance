package com.navix.verification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.navix.common.verification.EsignPort;
import com.navix.verification.client.SignzyContractClient;
import com.navix.verification.service.MockEsignAdapter;
import org.junit.jupiter.api.Test;

/**
 * The fail-loud guard on Aadhaar eSign.
 *
 * <p>{@code navix.esign.callback-url} defaulted to an empty string, {@code SignzyEsignAdapter.initiate}
 * sent it verbatim, and Signzy — whose contract spec marks the field mandatory — rejected every single
 * contract with {@code 400 "callbackUrl is not allowed to be empty"}. 156 attempts across 119 borrowers
 * between 2026-08-21 and 2026-09-16, not one success, ever. Nobody noticed for four weeks because
 * {@code esignInit} catches the failure and quietly offers the drawn-signature fallback, so every screen
 * looked normal and every loan agreement signed in that window was signed by finger rather than by
 * Aadhaar.
 *
 * <p>A misconfiguration that degrades silently is one that stays broken. This one now takes the deploy
 * down with it, which ECS reports in minutes instead of a month — and these tests are what keep the
 * guard from being softened back into a warning.
 */
class EsignConfigTest {

    private static EsignProperties props(String callbackUrl, String callbackSecret) {
        return new EsignProperties("signzy", callbackUrl, callbackSecret,
                "0.90", null, "#0C2540", "#E9B53A", "NAVIX Finance Private Limited");
    }

    @Test
    void signzyAdapterRefusesABlankCallbackUrl() {
        assertThatThrownBy(() -> new EsignConfig()
                .signzyEsignAdapter(mock(SignzyContractClient.class), props("", "a-secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("navix.esign.callback-url")
                // The message has to be actionable at 3am: both the env var and the escape hatch.
                .hasMessageContaining("NAVIX_ESIGN_CALLBACK_URL")
                .hasMessageContaining("navix.esign.provider=mock");
    }

    @Test
    void signzyAdapterRefusesANullCallbackUrl() {
        assertThatThrownBy(() -> new EsignConfig()
                .signzyEsignAdapter(mock(SignzyContractClient.class), props(null, "a-secret")))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * A relative path is the shape a well-meaning edit produces, and it fails in exactly the same way a
     * blank one does: Signzy has to be able to reach the URL from the public internet.
     */
    @Test
    void signzyAdapterRefusesACallbackUrlThatIsNotAbsolute() {
        assertThatThrownBy(() -> new EsignConfig().signzyEsignAdapter(
                mock(SignzyContractClient.class), props("/api/webhooks/signzy/contract", "a-secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absolute");
    }

    /**
     * The blank secret is fatal for a different reason, and just as quietly:
     * {@code SignzyContractWebhookController} compares it to the callback's Authorization header and
     * rejects everything while it is empty — so a contract minted with a callback URL we cannot
     * authenticate has no accelerator at all.
     */
    @Test
    void signzyAdapterRefusesABlankCallbackSecret() {
        assertThatThrownBy(() -> new EsignConfig().signzyEsignAdapter(
                mock(SignzyContractClient.class),
                props("https://api.dhanboost.test/api/webhooks/signzy/contract", "  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("navix.esign.callback-secret");
    }

    @Test
    void signzyAdapterBuildsWhenBothArePresent() {
        EsignPort port = new EsignConfig().signzyEsignAdapter(
                mock(SignzyContractClient.class),
                props("https://api.dhanboost.test/api/webhooks/signzy/contract", "a-long-random-secret"));

        assertThat(port).isNotNull();
    }

    @Test
    void anHttpCallbackUrlIsAccepted() {
        // Only the ALB's plain-HTTP hostname may be publicly reachable at first. The callback merely
        // accelerates a poll that re-reads from Signzy, so a forged one cannot forge a signature —
        // refusing http:// here would block the fix for no security gain.
        EsignPort port = new EsignConfig().signzyEsignAdapter(
                mock(SignzyContractClient.class),
                props("http://navix-alb.ap-south-1.elb.amazonaws.test/api/webhooks/signzy/contract", "s"));

        assertThat(port).isNotNull();
    }

    /**
     * The guard must not reach the mock. Local runs, the demo seed script and the whole test profile
     * set {@code navix.esign.provider=mock} and carry no callback settings at all — if the mock bean
     * validated them, this change would have stopped every developer's machine from booting.
     */
    @Test
    void theMockAdapterNeedsNoCallbackSettings() {
        assertThat(new EsignConfig().mockEsignAdapter()).isInstanceOf(MockEsignAdapter.class);
    }
}
