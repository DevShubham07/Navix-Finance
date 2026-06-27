package com.navix.verification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Builds the two outbound {@link RestClient} beans used by this module:
 * <ul>
 *   <li>{@code fintrixRestClient} - Authorization: Basic base64(clientId:clientSecret)</li>
 *   <li>{@code digiLockerRestClient} - headers X-Client-ID / X-Client-Secret</li>
 * </ul>
 *
 * <p>Both beans share a {@link SimpleClientHttpRequestFactory} with a 5s connect / 30s read
 * timeout so a slow upstream cannot hang a request thread indefinitely.
 */
@Configuration
@EnableConfigurationProperties({FintrixProperties.class, DigiLockerProperties.class})
public class FintrixClientConfig {

    public static final String FINTRIX_CLIENT = "fintrixRestClient";
    public static final String DIGILOCKER_CLIENT = "digiLockerRestClient";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private static ClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    @Bean(FINTRIX_CLIENT)
    public RestClient fintrixRestClient(FintrixProperties props) {
        String token = Base64.getEncoder().encodeToString(
                (props.clientId() + ":" + props.clientSecret()).getBytes(StandardCharsets.UTF_8));
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(timeoutRequestFactory())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + token)
                .build();
    }

    @Bean(DIGILOCKER_CLIENT)
    public RestClient digiLockerRestClient(DigiLockerProperties props) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(timeoutRequestFactory())
                .defaultHeader("X-Client-ID", props.clientId())
                .defaultHeader("X-Client-Secret", props.clientSecret())
                .build();
    }
}
