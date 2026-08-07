package com.navix.verification.config;

import com.navix.common.verification.EsignPort;
import com.navix.verification.service.MockEsignAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link EsignPort} seam. Today that is only {@link MockEsignAdapter}; the day a real
 * provider ships, declaring its bean removes this one from the context with no other change — and
 * nothing downstream knows which is wired.
 *
 * <p>A {@code @Bean} method rather than a scanned {@code @Component}, because that is the only place
 * {@code @ConditionalOnMissingBean} is reliable: on a component it evaluates against a partially
 * populated registry and can skip registration entirely.
 */
@Configuration
public class EsignConfig {

    @Bean
    @ConditionalOnMissingBean(EsignPort.class)
    public EsignPort mockEsignAdapter() {
        return new MockEsignAdapter();
    }
}
