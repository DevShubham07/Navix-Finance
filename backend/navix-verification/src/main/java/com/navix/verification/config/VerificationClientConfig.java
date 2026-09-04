package com.navix.verification.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
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
 *   <li>{@code fintrixRestClient} — base {@code navix.fintrix.base-url}, HTTP Basic like Digitap. The
 *       bureau PRIMARY ({@code /crif_combine}); Digitap Credit Analytics is now the fallback.</li>
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
        SignzyProperties.class, DigitapProperties.class, FintrixProperties.class,
        VerificationChainProperties.class})
public class VerificationClientConfig {

    public static final String SIGNZY_CLIENT = "signzyRestClient";
    public static final String SIGNZY_PROD_CLIENT = "signzyProdRestClient";
    public static final String DIGITAP_SVC_CLIENT = "digitapSvcRestClient";
    public static final String DIGITAP_API_CLIENT = "digitapApiRestClient";
    /** Digitap Credit Analytics — same host and auth as {@link #DIGITAP_API_CLIENT}, longer read. */
    public static final String DIGITAP_CREDIT_CLIENT = "digitapCreditRestClient";
    /** Digitap Credit Analytics CRIF — the SVC host (not api), same auth, its own tighter read. */
    public static final String DIGITAP_CRIF_CLIENT = "digitapCrifRestClient";
    /** Signzy Experian + CRIF — same host and auth as {@link #SIGNZY_CLIENT}, shorter read. */
    public static final String SIGNZY_BUREAU_CLIENT = "signzyBureauRestClient";
    /** Fintrix {@code /crif_combine} — the bureau PRIMARY. HTTP Basic, like Digitap. */
    public static final String FINTRIX_CLIENT = "fintrixRestClient";

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

    /**
     * Every provider client shares one lenient JSON converter.
     *
     * <p>Spring's default Jackson converter accepts {@code application/json} only, so whenever
     * Signzy, Fintrix or Digitap labelled an otherwise-perfect JSON body
     * {@code application/octet-stream}, {@code ProviderJson}'s {@code .toEntity(JsonNode.class)}
     * threw before we ever saw the content. The audit row for those calls holds
     * {@code httpStatus: null} and {@code response: null} — the answer was lost on OUR side of the
     * wire, not the provider's. All eight beans were built identically with default converters, so
     * this is fixed once here rather than eight times.
     *
     * <p><b>{@link MediaType#APPLICATION_JSON} MUST stay element 0.</b>
     * {@code AbstractHttpMessageConverter.getDefaultContentType()} returns the first supported type
     * and {@code DefaultRestClient} writes the request body with a null contentType — so putting
     * octet-stream or text/plain first would silently change the {@code Content-Type} of every
     * outbound provider POST, and every provider would start rejecting us with 400/415.
     *
     * <p>Deliberately <b>not</b> {@link MediaType#ALL}: these clients only ever read a
     * {@code JsonNode}, and a converter claiming everything would start intercepting reads it cannot
     * parse. The list is mutated in place, so the default byte[]/String/Resource converters survive —
     * do not switch to the {@code Iterable} overload of {@code messageConverters}, which replaces
     * the whole list.
     */
    private static void lenientJson(List<HttpMessageConverter<?>> converters) {
        MappingJackson2HttpMessageConverter json = new MappingJackson2HttpMessageConverter();
        json.setSupportedMediaTypes(List.of(
                MediaType.APPLICATION_JSON,
                MediaType.valueOf("application/*+json"),
                MediaType.APPLICATION_OCTET_STREAM,
                MediaType.TEXT_PLAIN,
                MediaType.valueOf("text/json")));
        // Replace IN PLACE. Removing and appending puts this converter behind Spring's YAML converter
        // (registered whenever jackson-dataformat-yaml is on the classpath — springdoc pulls it into
        // navix-app), and the FIRST converter that canWrite the body picks the Content-Type. That is
        // how every provider POST went out as `application/yaml`.
        int at = -1;
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof MappingJackson2HttpMessageConverter) {
                at = i;
                break;
            }
        }
        if (at >= 0) {
            converters.set(at, json);
        } else {
            converters.add(0, json);
        }
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
                .messageConverters(VerificationClientConfig::lenientJson)
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
                .messageConverters(VerificationClientConfig::lenientJson)
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
                .messageConverters(VerificationClientConfig::lenientJson)
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
                .messageConverters(VerificationClientConfig::lenientJson)
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
                .messageConverters(VerificationClientConfig::lenientJson)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic(props.clientId(), props.clientSecret()))
                .build();
    }

    @Bean(DIGITAP_CRIF_CLIENT)
    public RestClient digitapCrifRestClient(DigitapProperties props, VerificationChainProperties timeouts) {
        // NOTE the SVC base URL: the CRIF product lives on svc.digitap.ai, while the Experian one
        // (digitapCreditRestClient below) is on api.digitap.ai. Same credentials, different host — so
        // this cannot just reuse the credit client.
        return RestClient.builder()
                .baseUrl(props.svcBaseUrl())
                .requestFactory(timeoutRequestFactory(
                        timeouts.connectTimeout(), timeouts.digitapCrifReadTimeout()))
                .messageConverters(VerificationClientConfig::lenientJson)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic(props.clientId(), props.clientSecret()))
                .build();
    }

    @Bean(DIGITAP_CREDIT_CLIENT)
    public RestClient digitapCreditRestClient(DigitapProperties props, VerificationChainProperties timeouts) {
        // Identical to digitapApiRestClient apart from the read timeout. Kept separate so a hung
        // reverse-geocode or face-match still fails at 30s instead of pinning a worker for 60s.
        return RestClient.builder()
                .baseUrl(props.apiBaseUrl())
                .requestFactory(timeoutRequestFactory(
                        timeouts.connectTimeout(), timeouts.bureauReadTimeout()))
                .messageConverters(VerificationClientConfig::lenientJson)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic(props.clientId(), props.clientSecret()))
                .build();
    }

    @Bean(FINTRIX_CLIENT)
    public RestClient fintrixRestClient(FintrixProperties props, VerificationChainProperties timeouts) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(timeoutRequestFactory(
                        timeouts.connectTimeout(), timeouts.fintrixBureauReadTimeout()))
                .messageConverters(VerificationClientConfig::lenientJson)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic(props.clientId(), props.clientSecret()))
                // Fintrix authenticates its two endpoints DIFFERENTLY: /crif_combine was verified
                // working on Basic, /bureau_ch_user_auth on these two headers. We send the superset so
                // each endpoint receives the scheme it was verified with, rather than maintaining a
                // second RestClient bean for one extra route.
                // NOT verified: whether Basic alone also satisfies /bureau_ch_user_auth, or whether
                // these headers are inert on /crif_combine. Watch the first live call on each after a
                // deploy (ADMIN Provider API dashboard); if /crif_combine regresses, split the bean.
                .defaultHeader("X-Client-ID", props.clientId() == null ? "" : props.clientId())
                .defaultHeader("X-Client-Secret", props.clientSecret() == null ? "" : props.clientSecret())
                .build();
    }
}
