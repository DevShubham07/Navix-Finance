package com.navix.verification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Builds the two outbound {@link RestClient} beans used by this module:
 * <ul>
 *   <li>{@code fintrixRestClient} - Authorization: Basic base64(clientId:clientSecret)</li>
 *   <li>{@code digiLockerRestClient} - headers X-Client-ID / X-Client-Secret</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties({FintrixProperties.class, DigiLockerProperties.class})
public class FintrixClientConfig {

    public static final String FINTRIX_CLIENT = "fintrixRestClient";
    public static final String DIGILOCKER_CLIENT = "digiLockerRestClient";

    @Bean(FINTRIX_CLIENT)
    public RestClient fintrixRestClient(FintrixProperties props) {
        // TODO: add timeouts + a request/response logging interceptor for audit.
        String token = Base64.getEncoder().encodeToString(
                (props.clientId() + ":" + props.clientSecret()).getBytes(StandardCharsets.UTF_8));
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + token)
                .build();
    }

    @Bean(DIGILOCKER_CLIENT)
    public RestClient digiLockerRestClient(DigiLockerProperties props) {
        // TODO: add timeouts + error handling for DigiLocker non-2xx envelopes.
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("X-Client-ID", props.clientId())
                .defaultHeader("X-Client-Secret", props.clientSecret())
                .build();
    }
}
