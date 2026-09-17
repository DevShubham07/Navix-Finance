package com.navix.app.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.verification.config.DigitapProperties;
import com.navix.verification.config.FintrixProperties;
import com.navix.verification.config.SignzyProperties;
import com.navix.verification.config.VerificationChainProperties;
import com.navix.verification.config.VerificationClientConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * The converter-order regression, pinned on the only classpath where it reproduces.
 *
 * <p>On 2026-09-04 every outbound provider POST started going out as {@code Content-Type:
 * application/yaml}, and Signzy, Digitap and Fintrix all rejected us. The cause was one line in
 * {@code VerificationClientConfig.lenientJson}: it removed Spring's Jackson converter and appended the
 * lenient replacement, which put it BEHIND the YAML converter Spring registers whenever
 * {@code jackson-dataformat-yaml} is on the classpath. {@code DefaultRestClient} writes the body with
 * the first converter whose {@code canWrite} accepts it and takes the Content-Type from that converter,
 * so YAML won every time.
 *
 * <p><b>Why this test lives in navix-app and not beside the config it tests.</b> Nothing in
 * {@code navix-verification} pulls in the YAML converter, so an identical test there passes whether the
 * bug is present or not. {@code springdoc-openapi-starter-webmvc-ui} drags {@code jackson-dataformat-yaml}
 * onto this module's classpath and only this one — which is also why the bug reached production while
 * the verification module's own suite stayed green. Keep the test here even though the code is there.
 */
class VerificationClientConverterOrderTest {

    private static VerificationChainProperties chain() {
        return new VerificationChainProperties(List.of("signzy", "digitap", "fintrix"),
                null, null, null, null, null, null, null);
    }

    /** Sanity check: without this, the assertions below would be vacuous. */
    @Test
    void theYamlConverterIsOnThisModulesClasspath() {
        assertThat(defaultConverterTypes())
                .as("springdoc should be pulling jackson-dataformat-yaml in; if it no longer does, "
                        + "this whole test class stops reproducing the Sep-2026 regression")
                .anyMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("yaml"));
    }

    @Test
    void everyProviderClientWritesItsBodyWithJsonNotYaml() {
        List<RestClient> clients = List.of(
                new VerificationClientConfig().signzyRestClient(
                        new SignzyProperties("https://signzy.test", "tok", "cid", null, null), chain()),
                new VerificationClientConfig().fintrixRestClient(
                        new FintrixProperties("https://fintrix.test", "id", "secret"), chain()),
                new VerificationClientConfig().digitapSvcRestClient(
                        new DigitapProperties("https://svc.digitap.test", "https://api.digitap.test",
                                "id", "secret"), chain()),
                new VerificationClientConfig().digitapApiRestClient(
                        new DigitapProperties("https://svc.digitap.test", "https://api.digitap.test",
                                "id", "secret"), chain()));

        for (RestClient client : clients) {
            HttpMessageConverter<?> first = convertersOf(client).stream()
                    .filter(c -> c.canWrite(Map.class, null))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No converter can write a Map body"));
            assertThat(first).isInstanceOf(MappingJackson2HttpMessageConverter.class);
            // getDefaultContentType() returns the first supported type, and DefaultRestClient writes
            // with a null contentType — so element 0 IS the header every provider will see.
            assertThat(first.getSupportedMediaTypes().get(0)).isEqualTo(MediaType.APPLICATION_JSON);
        }
    }

    private static List<HttpMessageConverter<?>> convertersOf(RestClient client) {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        client.mutate().messageConverters(converters::addAll);
        return converters;
    }

    private static List<String> defaultConverterTypes() {
        return convertersOf(RestClient.builder().build()).stream()
                .map(c -> c.getClass().getName())
                .toList();
    }
}
