package com.navix.verification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.navix.verification.exception.VerificationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The audit trail behind the admin Provider API dashboard: every provider call must be recorded with
 * its exact request and response, whatever the outcome, and recording must never disturb the caller.
 */
class ProviderCallLogTest {

    private final List<ProviderCall> recorded = new ArrayList<>();

    @BeforeEach
    void installRecorder() {
        recorded.clear();
        ProviderCallLog.setRecorder(call -> {
            recorded.add(call);
            return 42L;
        });
    }

    @AfterEach
    void reset() {
        ProviderCallLog.setRecorder(ProviderCallRecorder.NOOP);
        ProviderCallContext.clear();
    }

    @Test
    void recordsTheExactRequestAndResponseOfASuccessfulCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/credit_analytics/request"))
                .andRespond(withSuccess("{\"result_code\":101,\"request_id\":\"REQ-1\"}",
                        MediaType.APPLICATION_JSON));

        ProviderJson.post(builder.build(), "/credit_analytics/request", Map.of("pan", "ABCDE1234F"));

        assertThat(recorded).hasSize(1);
        ProviderCall call = recorded.get(0);
        assertThat(call.status()).isEqualTo(ProviderCall.SUCCESS);
        assertThat(call.httpStatus()).isEqualTo(200);
        assertThat(call.provider()).isEqualTo("DIGITAP");
        assertThat(call.operation()).isEqualTo("BUREAU");
        assertThat(call.requestJson()).isEqualTo("{\"pan\":\"ABCDE1234F\"}");
        assertThat(call.responseJson()).contains("\"request_id\":\"REQ-1\"");
        assertThat(call.errorMessage()).isNull();
        server.verify();
    }

    @Test
    void recordsTheRawErrorBodyOfAFailedCallAndStillThrows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/api/v3/bureau/crif"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"No remaining API credits.\"}"));

        assertThatThrownBy(() -> ProviderJson.post(builder.build(), "/api/v3/bureau/crif", Map.of()))
                .isInstanceOf(VerificationException.class);

        assertThat(recorded).hasSize(1);
        ProviderCall call = recorded.get(0);
        assertThat(call.failed()).isTrue();
        assertThat(call.httpStatus()).isEqualTo(403);
        assertThat(call.provider()).isEqualTo("SIGNZY_CRIF");
        // The exception detail is redacted; the audit row keeps the raw provider wording.
        assertThat(call.responseJson()).contains("No remaining API credits.");
        server.verify();
    }

    @Test
    void marksATwoHundredCarryingAnErrorEnvelopeAsFailed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/validation/kyc/v1/pan_details_plus"))
                .andRespond(withSuccess("{\"result_code\":102,\"message\":\"not verified\"}",
                        MediaType.APPLICATION_JSON));

        ProviderJson.post(builder.build(), "/validation/kyc/v1/pan_details_plus", Map.of());

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).failed()).isTrue();
        assertThat(recorded.get(0).httpStatus()).isEqualTo(200);
        server.verify();
    }

    @Test
    void aToleratedStatusIsRecordedAsANormalOutcomeAndReturnsNull() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/api/v3/liveness-secure/getData"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Video Verification is not completed till now\"}"));

        assertThat(ProviderJson.postTolerating(
                builder.build(), "/api/v3/liveness-secure/getData", Map.of("token", "t"), 404)).isNull();

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).status()).isEqualTo(ProviderCall.SUCCESS);
        assertThat(recorded.get(0).httpStatus()).isEqualTo(404);
        assertThat(recorded.get(0).operation()).isEqualTo("LIVENESS");
        server.verify();
    }

    @Test
    void aRecorderThatThrowsNeverBreaksTheProviderCall() {
        ProviderCallLog.setRecorder(call -> {
            throw new IllegalStateException("audit table is down");
        });
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/api/v3/pan/compliance-206-individual-search"))
                .andRespond(withSuccess("{\"result\":{\"verified\":true}}", MediaType.APPLICATION_JSON));

        assertThat(ProviderJson.post(builder.build(),
                "/api/v3/pan/compliance-206-individual-search", Map.of()))
                .isNotNull();
        server.verify();
    }

    @Test
    void recordsATransportFailureThatNeverGotAnHttpStatus() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/credit_analytics/request"))
                .andRespond(request -> {
                    throw new java.net.SocketTimeoutException("Read timed out");
                });

        assertThatThrownBy(() -> ProviderJson.post(
                builder.build(), "/credit_analytics/request", Map.of("pan", "ABCDE1234F")))
                .isInstanceOf(RuntimeException.class);

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).failed()).isTrue();
        assertThat(recorded.get(0).httpStatus()).isNull();
        assertThat(recorded.get(0).errorMessage()).contains("Read timed out");
    }

    @Test
    void serverErrorsAreRecordedToo() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/ent/v1/address-verification")).andRespond(withServerError());

        assertThatThrownBy(() -> ProviderJson.post(builder.build(), "/ent/v1/address-verification", Map.of()))
                .isInstanceOf(VerificationException.class);

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).httpStatus()).isEqualTo(500);
        assertThat(recorded.get(0).operation()).isEqualTo("ADDRESS");
        server.verify();
    }
}
