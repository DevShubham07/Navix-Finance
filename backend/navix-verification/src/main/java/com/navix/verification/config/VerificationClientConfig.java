package com.navix.verification.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the outbound {@link RestClient} beans for the two verification providers:
 * <ul>
 *   <li>{@code signzyRestClient} — base {@code navix.signzy.base-url}, header
 *       {@code Authorization: <raw token>} (Signzy's opaque token — NOT {@code Basic}/{@code Bearer}).</li>
 *   <li>{@code digitapSvcRestClient} — base {@code navix.digitap.svc-base-url} (KYC/Employment/Email),
 *       header {@code Authorization: Basic base64(clientId:clientSecret)}.</li>
 *   <li>{@code digitapApiRestClient} — base {@code navix.digitap.api-base-url}
 *       (Credit/Location/Face-Match/OCR), same Basic auth.</li>
 * </ul>
 *
 * <p>Each gets a {@link SimpleClientHttpRequestFactory} whose connect/read timeouts come from
 * {@code navix.verification.*} (defaults 5s connect / 30s read), so a slow upstream cannot hang a
 * request thread indefinitely. Two beans deliberately deviate: {@code digitapCreditRestClient} allows
 * a much longer read because Digitap Credit Analytics is slow, and {@code signzyBureauRestClient} a
 * much shorter one so the three-leg bureau chain still fits inside the ALB idle timeout.
 * Base URLs default to the PREPRODUCTION hosts and are overridable via env (see the properties records).
 */
@Configuration
@EnableConfigurationProperties({
        SignzyProperties.class, DigitapProperties.class, VerificationChainProperties.class})
public class VerificationClientConfig {

    public static final String SIGNZY_CLIENT = "signzyRestClient";
    public static final String SIGNZY_PROD_CLIENT = "signzyProdRestClient";
    public static final String DIGITAP_SVC_CLIENT = "digitapSvcRestClient";
    public static final String DIGITAP_API_CLIENT = "digitapApiRestClient";
    /** Digitap Credit Analytics — same host and auth as {@link #DIGITAP_API_CLIENT}, longer read. */
    public static final String DIGITAP_CREDIT_CLIENT = "digitapCreditRestClient";
    /** Signzy Experian + CRIF — same host and auth as {@link #SIGNZY_CLIENT}, shorter read. */
    public static final String SIGNZY_BUREAU_CLIENT = "signzyBureauRestClient";

    /**
     * {@link SimpleClientHttpRequestFactory} has no per-request timeout override, so a client that
     * needs a different read timeout genuinely needs its own factory (and therefore its own bean).
     */
    private static ClientHttpRequestFactory timeoutRequestFactory(Duration connect, Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        return factory;
    }

    private static String basic(String clientId, String clientSecret) {
        String token = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    @Bean(SIGNZY_CLIENT)
    public RestClient signzyRestClient(SignzyProperties props, VerificationChainProperties timeouts) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(timeoutRequestFactory(timeouts.connectTimeout(), timeouts.readTimeout()))
                // Signzy expects the RAW opaque token in Authorization (no "Basic"/"Bearer" prefix)
                // plus the account's unique id in x-client-unique-id.
                .defaultHeader(HttpHeaders.AUTHORIZATION, props.token() == null ? "" : props.token())
                .defaultHeader("x-client-unique-id",
                        props.clientUniqueId() == null ? "" : props.clientUniqueId())
                .build();
    }

    @Bean(SIGNZY_PROD_CLIENT)
    public RestClient signzyProdRestClient(SignzyProperties props, VerificationChainProperties timeouts) {
        // Signzy production account (PAN + reverse-geocode). Same raw-token + x-client-unique-id scheme
        // as the preprod client, different base URL + token.
        return RestClient.builder()
                .baseUrl(props.prodBaseUrl())
                .requestFactory(timeoutRequestFactory(timeouts.connectTimeout(), timeouts.readTimeout()))
                .defaultHeader(HttpHeaders.AUTHORIZATION, props.prodToken() == null ? "" : props.prodToken())
                .defaultHeader("x-client-unique-id",
                        props.clientUniqueId() == null ? "" : props.clientUniqueId())
                .build();
    }

    @Bean(DIGITAP_SVC_CLIENT)
    public RestClient digitapSvcRestClient(DigitapProperties props, VerificationChainProperties timeouts) {
        return RestClient.builder()
                .baseUrl(props.svcBaseUrl())
                .requestFactory(timeoutRequestFactory(timeouts.connectTimeout(), timeouts.readTimeout()))
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic(props.clientId(), props.clientSecret()))
                .build();
    }

    @Bean(SIGNZY_BUREAU_CLIENT)
    public RestClient signzyBureauRestClient(SignzyProperties props, VerificationChainProperties timeouts) {
        // Identical to signzyRestClient apart from the read timeout — see the class Javadoc.
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(timeoutRequestFactory(
                        timeouts.connectTimeout(), timeouts.signzyBureauReadTimeout()))
                .defaultHeader(HttpHeaders.AUTHORIZATION, props.token() == null ? "" : props.token())
                .defaultHeader("x-client-unique-id",
                        props.clientUniqueId() == null ? "" : props.clientUniqueId())
                .build();
    }

    @Bean(DIGITAP_API_CLIENT)
    public RestClient digitapApiRestClient(DigitapProperties props, VerificationChainProperties timeouts) {
        return RestClient.builder()
                .baseUrl(props.apiBaseUrl())
                .requestFactory(timeoutRequestFactory(timeouts.connectTimeout(), timeouts.readTimeout()))
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic(props.clientId(), props.clientSecret()))
                .build();
    }

    @Bean(DIGITAP_CREDIT_CLIENT)
    public RestClient digitapCreditRestClient(DigitapProperties props, VerificationChainProperties timeouts) {
        // Identical to digitapApiRestClient apart from the read timeout. Kept separate so a hung
        // reverse-geocode or face-match still fails at 30s instead of pinning a worker for 90s.
        return RestClient.builder()
                .baseUrl(props.apiBaseUrl())
                .requestFactory(timeoutRequestFactory(
                        timeouts.connectTimeout(), timeouts.bureauReadTimeout()))
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic(props.clientId(), props.clientSecret()))
                .build();
    }
}
