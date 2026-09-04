package com.navix.app.verification;

import com.navix.verification.config.FintrixProperties;
import com.navix.verification.config.SignzyProperties;
import com.navix.verification.config.VerificationChainProperties;
import com.navix.verification.config.VerificationClientConfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Pins the outbound {@code Content-Type} of every provider POST to {@code application/json}.
 *
 * <p><b>Why this test exists.</b> {@code ProviderJson.post} calls {@code client.post().uri(..).body(..)}
 * without an explicit {@code contentType(..)}, so the header is chosen by whichever
 * {@code HttpMessageConverter} in the client's list first claims it can write the body. Spring Boot
 * registers a YAML converter ahead of ours whenever {@code jackson-dataformat-yaml} is on the
 * classpath, and it is (springdoc pulls it in). When {@code lenientJson} appended its JSON converter
 * to the END of the list instead of the front, YAML won and every provider POST went out as
 * {@code Content-Type: application/yaml} — Signzy answered 415, Fintrix 400 "not valid JSON",
 * Digitap 412, and the whole verification pipeline stopped resolving names for 5 hours in production.
 *
 * <p>The assertion is made against a real socket rather than a mock so it captures the header that is
 * genuinely on the wire, not one a test double reports.
 *
 * <p><b>This test MUST live in navix-app, not in navix-verification.</b> The YAML converter only
 * appears when {@code jackson-dataformat-yaml} is on the classpath, and that arrives via springdoc,
 * which is declared only in navix-app. The identical test placed next to the config it covers passes
 * green while production is fully broken — which is exactly how this shipped.
 */
class VerificationClientContentTypeTest {

    private HttpServer server;
    private final AtomicReference<String> seenContentType = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static VerificationChainProperties timeouts() {
        return new VerificationChainProperties(
                List.of("signzy"), 5, 30, null, null, null, null);
    }

    private void postAndAssertJson(RestClient client) {
        client.post().uri("/probe").body(Map.of("panNumber", "ABCDE1234F")).retrieve().toBodilessEntity();

        assertThat(seenContentType.get())
                .as("outbound Content-Type must be application/json, not YAML or anything else")
                .isNotNull()
                .startsWith("application/json");
        assertThat(seenBody.get())
                .as("body must be serialised as JSON")
                .contains("\"panNumber\"")
                .contains("ABCDE1234F");
    }

    @Test
    void signzyClient_sendsApplicationJson() {
        VerificationClientConfig config = new VerificationClientConfig();
        SignzyProperties props = new SignzyProperties(
                baseUrl(), "token", "uid", baseUrl(), "prodToken");
        postAndAssertJson(config.signzyRestClient(props, timeouts()));
    }

    @Test
    void signzyProdClient_sendsApplicationJson() {
        VerificationClientConfig config = new VerificationClientConfig();
        SignzyProperties props = new SignzyProperties(
                baseUrl(), "token", "uid", baseUrl(), "prodToken");
        postAndAssertJson(config.signzyProdRestClient(props, timeouts()));
    }

    @Test
    void fintrixClient_sendsApplicationJson() {
        VerificationClientConfig config = new VerificationClientConfig();
        FintrixProperties props = new FintrixProperties(baseUrl(), "id", "secret");
        postAndAssertJson(config.fintrixRestClient(props, timeouts()));
    }
}
