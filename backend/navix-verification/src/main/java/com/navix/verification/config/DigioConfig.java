package com.navix.verification.config;

import com.navix.common.verification.MandatePort;
import com.navix.verification.client.DigioMandateClient;
import com.navix.verification.service.MockMandateAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the {@link MandatePort} seam, selecting on {@code navix.digio.provider} in both directions exactly
 * as {@link EsignConfig} does (no order-dependent {@code @ConditionalOnMissingBean} fallback).
 */
@Configuration
@EnableConfigurationProperties(DigioProperties.class)
public class DigioConfig {

    /**
     * The real provider. Every {@code register} files a legally binding NPCI mandate request.
     *
     * <p><b>Refuses to start on a blank credential, mandate config id or webhook secret.</b> The lesson is
     * the eSign one: a missing parameter that merely degrades (Digio answers a bad config with a business
     * error wrapped in HTTP 500, which a journey step would swallow and fall back around) stays broken
     * unnoticed. Failing the boot surfaces it in minutes instead. Offline and local runs set
     * {@code navix.digio.provider=mock}.
     */
    @Bean
    @ConditionalOnProperty(name = "navix.digio.provider", havingValue = "digio", matchIfMissing = true)
    public MandatePort digioMandateAdapter(DigioProperties props, VerificationChainProperties timeouts) {
        require(props.baseUrl(), "base-url", "NAVIX_DIGIO_BASE_URL");
        require(props.clientId(), "client-id", "NAVIX_DIGIO_CLIENT_ID");
        require(props.clientSecret(), "client-secret", "NAVIX_DIGIO_CLIENT_SECRET");
        require(props.webhookSecret(), "webhook-secret", "NAVIX_DIGIO_WEBHOOK_SECRET");
        require(props.corporateConfigId(), "corporate-config-id", "NAVIX_DIGIO_CORPORATE_CONFIG_ID");
        // Explicit timeouts, shared with the Signzy/Digitap clients: without them a hung connection never
        // returns, so the timeout-then-findByRef recovery of an unknown register outcome is unreachable.
        return new DigioMandateClient(props, RestClient.builder()
                .requestFactory(timeoutFactory(timeouts.connectTimeout(), timeouts.readTimeout())));
    }

    static SimpleClientHttpRequestFactory timeoutFactory(Duration connect, Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        return factory;
    }

    private static void require(String value, String key, String env) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "navix.digio." + key + " must be set when navix.digio.provider=digio (set " + env
                            + " / SSM /navix/<env>/navix/digio/" + key
                            + ", or set navix.digio.provider=mock for an offline run)");
        }
    }

    /** Booting on the mock is legal (CI, local, and prod while the flag is off); SERVING on it in prod is not. */
    @Bean
    @ConditionalOnProperty(name = "navix.digio.provider", havingValue = "mock")
    public MandatePort mockMandateAdapter(@org.springframework.beans.factory.annotation.Value("${NAVIX_ENV:dev}") String env) {
        return new MockMandateAdapter(env);
    }
}
