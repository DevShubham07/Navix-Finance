package com.navix.verification.config;

import com.navix.common.verification.EsignPort;
import com.navix.verification.client.SignzyContractClient;
import com.navix.verification.service.MockEsignAdapter;
import com.navix.verification.service.SignzyEsignAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link EsignPort} seam. Signzy Contract eSign (real Aadhaar eSign) is the default; the mock
 * is reachable only by naming it — {@code navix.esign.provider=mock} — which the demo seed script and the
 * unit tests do. Selecting on the property in both directions rather than leaving the mock as a
 * {@code @ConditionalOnMissingBean} fallback keeps the choice explicit: two {@code @Bean} methods in one
 * class are evaluated in declaration order, so a missing-bean fallback here would be quietly
 * order-dependent.
 *
 * <p>{@code @Bean} methods rather than scanned {@code @Component}s, because a condition on a component is
 * evaluated against a partially populated registry and can skip registration entirely.
 */
@Configuration
@EnableConfigurationProperties(EsignProperties.class)
public class EsignConfig {

    /**
     * The real provider. Every {@code initiate} mints a billable, legally binding contract, and there is
     * no Signzy sandbox for it — the preproduction account is not entitled.
     *
     * <p><b>Refuses to start without the callback settings</b>, and that is the point. Signzy's contract
     * spec marks {@code callbackUrl} mandatory; ours defaulted to an empty string, the adapter sent it
     * verbatim, and Signzy rejected every single contract with
     * {@code 400 "callbackUrl is not allowed to be empty"} — 156 attempts across 119 borrowers between
     * 2026-08-21 and 2026-09-16, not one success, ever. Nobody noticed for four weeks because
     * {@code esignInit} catches the failure and quietly offers the drawn-signature fallback, so the
     * screens looked normal and every loan agreement in that window was signed by finger instead of by
     * Aadhaar. A misconfiguration that degrades silently is one that stays broken; this one now takes
     * the deploy down with it, which ECS reports in minutes rather than a month.
     *
     * <p>The blank secret is equally fatal in practice:
     * {@code SignzyContractWebhookController} compares it to the callback's Authorization header and
     * rejects everything while it is empty, so a contract minted with a callback URL we cannot
     * authenticate has no accelerator at all.
     *
     * <p>Offline and local runs set {@code navix.esign.provider=mock} (the demo seed script and the
     * test profile already do) — the message says so.
     */
    @Bean
    @ConditionalOnProperty(name = "navix.esign.provider", havingValue = "signzy", matchIfMissing = true)
    public EsignPort signzyEsignAdapter(SignzyContractClient client, EsignProperties props) {
        if (!isAbsoluteUrl(props.callbackUrl())) {
            throw new IllegalStateException(
                    "navix.esign.callback-url must be an absolute http(s) URL when "
                            + "navix.esign.provider=signzy — Signzy rejects every contract without one "
                            + "(set NAVIX_ESIGN_CALLBACK_URL / SSM /navix/<env>/navix/esign/callback-url "
                            + "to https://<public-backend>/api/webhooks/signzy/contract, or set "
                            + "navix.esign.provider=mock for an offline run)");
        }
        if (props.callbackSecret() == null || props.callbackSecret().isBlank()) {
            throw new IllegalStateException(
                    "navix.esign.callback-secret must be set when navix.esign.provider=signzy — the "
                            + "contract webhook rejects every callback while it is blank (set "
                            + "NAVIX_ESIGN_CALLBACK_SECRET / SSM "
                            + "/navix/<env>/navix/esign/callback-secret, or set "
                            + "navix.esign.provider=mock for an offline run)");
        }
        return new SignzyEsignAdapter(client, props);
    }

    private static boolean isAbsoluteUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    @Bean
    @ConditionalOnProperty(name = "navix.esign.provider", havingValue = "mock")
    public EsignPort mockEsignAdapter() {
        return new MockEsignAdapter();
    }
}
