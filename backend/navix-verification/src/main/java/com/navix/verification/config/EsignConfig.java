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
     */
    @Bean
    @ConditionalOnProperty(name = "navix.esign.provider", havingValue = "signzy", matchIfMissing = true)
    public EsignPort signzyEsignAdapter(SignzyContractClient client, EsignProperties props) {
        return new SignzyEsignAdapter(client, props);
    }

    @Bean
    @ConditionalOnProperty(name = "navix.esign.provider", havingValue = "mock")
    public EsignPort mockEsignAdapter() {
        return new MockEsignAdapter();
    }
}
