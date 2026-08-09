package com.navix.verification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;

import com.navix.verification.exception.VerificationException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ProviderJsonTest {

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
