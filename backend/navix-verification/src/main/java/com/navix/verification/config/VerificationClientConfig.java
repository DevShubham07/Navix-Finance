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
 * <p>All three share a {@link SimpleClientHttpRequestFactory} with a 5s connect / 30s read timeout so a
 * slow upstream cannot hang a request thread indefinitely (mirrors the retired {@code FintrixClientConfig}).
 * Base URLs default to the PREPRODUCTION hosts and are overridable via env (see the properties records).
 */
@Configuration
@EnableConfigurationProperties({
        SignzyProperties.class, DigitapProperties.class, VerificationChainProperties.class})
public class VerificationClientConfig {

    public static final String SIGNZY_CLIENT = "signzyRestClient";
    public static final String DIGITAP_SVC_CLIENT = "digitapSvcRestClient";
    public static final String DIGITAP_API_CLIENT = "digitapApiRestClient";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private static ClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    private static String basic(String clientId, String clientSecret) {
        String token = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    @Bean(SIGNZY_CLIENT)
    public RestClient signzyRestClient(SignzyProperties props) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(timeoutRequestFactory())
                // Signzy expects the RAW opaque token in Authorization (no "Basic"/"Bearer" prefix)
                // plus the account's unique id in x-client-unique-id.
                .defaultHeader(HttpHeaders.AUTHORIZATION, props.token() == null ? "" : props.token())
                .defaultHeader("x-client-unique-id",
                        props.clientUniqueId() == null ? "" : props.clientUniqueId())
                .build();
    }

    @Bean(DIGITAP_SVC_CLIENT)
    public RestClient digitapSvcRestClient(DigitapProperties props) {
        return RestClient.builder()
                .baseUrl(props.svcBaseUrl())
                .requestFactory(timeoutRequestFactory())
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic(props.clientId(), props.clientSecret()))
                .build();
    }

    @Bean(DIGITAP_API_CLIENT)
    public RestClient digitapApiRestClient(DigitapProperties props) {
        return RestClient.builder()
                .baseUrl(props.apiBaseUrl())
                .requestFactory(timeoutRequestFactory())
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic(props.clientId(), props.clientSecret()))
                .build();
    }
}
