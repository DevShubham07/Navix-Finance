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
     * A body that is not JSON is now READ before it is judged, and the evidence is kept.
     *
     * <p>This test used to pin the opposite: the response was requested as a {@code JsonNode}, no
     * converter claimed {@code application/octet-stream}, and the call died inside the converter with
     * an {@code UnknownContentTypeException} — recorded as a transport failure with
     * {@code response: null}, which is the one field anybody debugging it would want. {@code post} now
     * reads bytes, decodes them explicitly, and parses afterwards, so the body reaches the audit row
     * wrapped as {@code __unparseable} (the column is {@code jsonb} and would otherwise reject it).
     *
     * <p>What has NOT changed is the exception type: it is still a {@link VerificationException}, because
     * {@code RoutingVerificationPort.route()} catches only that and
     * {@code CapabilityNotSupportedException} — a raw {@code RestClientException} propagated past the
     * router and aborted the whole chain, which is how 25 applications in the Sep-2026 audit never
     * reached Digitap or Fintrix at all. {@code httpStatus} stays null on purpose so the caller does not
     * file a perfectly ordinary 200 as {@code HTTP_200}.
     */
    @Test
    void anUnparseableBodyIsAVerificationExceptionCarryingTheEndpointAndACode() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/pan"))
                .andRespond(withSuccess("not JSON at all", MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> ProviderJson.post(builder.build(), "/pan", Map.of("pan", "ABCDE1234F")))
                .isInstanceOfSatisfying(VerificationException.class, failure -> {
                    assertThat(failure.getMessage()).startsWith("Unparseable response body");
                    assertThat(failure.endpoint()).isEqualTo("/pan");
                    assertThat(failure.httpStatus()).isNull();
                    assertThat(failure.providerCode())
                            .isEqualTo(com.navix.common.verification.ProviderFailureDetails.UNPARSEABLE_RESPONSE);
                });
        server.verify();
    }

    /**
     * Digitap labels some perfectly good JSON {@code application/octet-stream}, which has no charset —
     * and {@code StringHttpMessageConverter} defaults an unrecognised content type to ISO-8859-1. Every
     * non-ASCII borrower name would come back mojibake. Reading bytes and decoding as UTF-8 explicitly
     * is what keeps that from happening, so it is worth a test of its own.
     */
    @Test
    void aBodyWithNoCharsetIsDecodedAsUtf8NotIsoLatin1() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/pan"))
                .andRespond(withSuccess(
                        "{\"name\":\"Nidhi Bhardwaj \u20b9\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        MediaType.APPLICATION_OCTET_STREAM));

        JsonNode result = ProviderJson.post(builder.build(), "/pan", Map.of());

        assertThat(result.path("name").asText()).isEqualTo("Nidhi Bhardwaj \u20b9");
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
