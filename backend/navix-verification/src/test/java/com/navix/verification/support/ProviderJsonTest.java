package com.navix.verification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.verification.config.SignzyProperties;
import com.navix.verification.config.VerificationChainProperties;
import com.navix.verification.config.VerificationClientConfig;
import com.navix.verification.exception.VerificationException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ProviderJsonTest {

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void everyCallLogsItsRawHttp200ProviderErrorEnvelope(CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/pan"))
                .andRespond(withSuccess(
                        "{\"result_code\":102,\"message\":\"PAN ABCDE1234F could not be verified\"}",
                        MediaType.APPLICATION_JSON));

        ProviderJson.post(builder.build(), "/pan", Map.of("pan", "ABCDE1234F"));

        assertThat(output).contains(
                "PROVIDER_CALL",
                "responsePayload={\"result_code\":102,\"message\":\"PAN ABCDE1234F could not be verified\"}");
        server.verify();
    }

    @Test
    void httpFailureRetainsOnlySafeDiagnosticFieldsAndRedactsPii() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/bureau"))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error_code":"INVALID_PAN","message":"PAN ABCDE1234F is invalid for 9876543210","name":"Kartik"}
                                """));

        assertThatThrownBy(() -> ProviderJson.post(builder.build(), "/bureau", Map.of("request", "test")))
                .isInstanceOfSatisfying(VerificationException.class, failure -> {
                    assertThat(failure.httpStatus()).isEqualTo(400);
                    assertThat(failure.endpoint()).isEqualTo("/bureau");
                    assertThat(failure.providerCode()).isEqualTo("INVALID_PAN");
                    assertThat(failure.safeDetail()).contains("[REDACTED]");
                    assertThat(failure.safeDetail()).doesNotContain("ABCDE1234F", "9876543210", "Kartik");
                });
        server.verify();
    }

    /**
     * A body that isn't valid JSON at all (not merely mislabelled — see
     * {@link #jsonBodyLabelledOctetStreamNowParsesInsteadOfThrowing} for that case) must still come back
     * as a {@link VerificationException}, not an opaque {@code RestClientException}, so
     * {@code RoutingVerificationPort.route()} (which catches only
     * {@code VerificationException}/{@code CapabilityNotSupportedException}) falls through instead of
     * aborting the whole provider chain.
     *
     * <p>{@code ProviderJson.post} reads the response as a raw {@code String} first — Spring's default
     * {@code StringHttpMessageConverter} accepts any {@code Content-Type} — so the transport itself
     * succeeds and the real HTTP status/body reach the audit trail even though the content can't be
     * parsed as JSON. Before that fix, the plain Jackson converter threw before the response was ever
     * read, and the audit row recorded {@code httpStatus: null} / {@code response: null} — the answer
     * was lost on our side of the wire, not the provider's.
     */
    @Test
    void undeserializableResponseIsWrappedAsVerificationExceptionCarryingTheEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/pan"))
                .andRespond(withSuccess("not read anyway", MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> ProviderJson.post(builder.build(), "/pan", Map.of("pan", "ABCDE1234F")))
                .isInstanceOfSatisfying(VerificationException.class, failure -> {
                    assertThat(failure.endpoint()).isEqualTo("/pan");
                    // The HTTP transport succeeded, so the real status is captured — only the JSON
                    // parse failed.
                    assertThat(failure.httpStatus()).isEqualTo(200);
                    assertThat(failure.providerCode()).isNull();
                });
        server.verify();
    }

    /**
     * F2: Signzy/Fintrix/Digitap all sometimes label a perfectly good JSON body
     * {@code application/octet-stream}. {@link VerificationClientConfig#lenientJson} is the fix, but it
     * is a private static method wired into every provider {@code RestClient} bean — rather than
     * duplicating its converter list here (and risking the copy drifting from the real one), this builds
     * an ACTUAL bean from {@link VerificationClientConfig} and swaps in a mock transport via
     * {@code RestClient#mutate()}, which carries the bean's configured message converters forward
     * unchanged. That is the same production wiring every provider client gets.
     */
    @Test
    void jsonBodyLabelledOctetStreamNowParsesInsteadOfThrowing() {
        RestClient signzyBean = new VerificationClientConfig().signzyRestClient(
                new SignzyProperties("https://provider.test", "tok", "cid", null, null),
                new VerificationChainProperties(null, null, null, null, null, null, null, null));
        RestClient.Builder mutated = signzyBean.mutate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(mutated).build();
        RestClient client = mutated.build();
        server.expect(requestTo("https://provider.test/pan"))
                .andRespond(withSuccess(
                        "{\"status\":\"success\",\"txnId\":\"OCTET-1\"}",
                        MediaType.APPLICATION_OCTET_STREAM));

        JsonNode result = ProviderJson.post(client, "/pan", Map.of("pan", "ABCDE1234F"));

        assertThat(result.path("txnId").asText()).isEqualTo("OCTET-1");
        server.verify();
    }
}
