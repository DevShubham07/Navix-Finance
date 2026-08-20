package com.navix.verification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
}
