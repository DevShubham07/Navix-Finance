package com.navix.verification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.navix.common.verification.ProviderCallContext;
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
    private final List<String> reclassified = new ArrayList<>();

    @BeforeEach
    void installRecorder() {
        recorded.clear();
        reclassified.clear();
        ProviderCallLog.setRecorder(new ProviderCallRecorder() {
            @Override
            public Long record(ProviderCall call) {
                recorded.add(call);
                return 42L;
            }

            @Override
            public void markFailed(Long executionId, String error) {
                reclassified.add(executionId + ":" + error);
            }
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

    /**
     * The audit status is the CALLER'S outcome, not a guess from the envelope's shape.
     *
     * <p>{@code postAllowingErrorEnvelope} exists precisely so a client can read an "error"-shaped body
     * as a legitimate ANSWER — Digitap's UAN {@code 103}/{@code 104} ("no EPFO record") and Fintrix's
     * "no data found in CRIF" are the two that matter. Marking those FAILED buried the real failures:
     * 1,939 of the 4,848 rows in the Sep-2026 audit were correct answers logged at ERROR, which took
     * the dashboard's apparent failure rate from ~10% to 24.5%.
     */
    @Test
    void aToleratedErrorEnvelopeIsRecordedAsSuccess() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/validation/kyc/v1/uan_basic_v3"))
                .andRespond(withSuccess(
                        "{\"result_code\":103,\"status\":\"error\",\"message\":\"No record found\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(ProviderJson.postAllowingErrorEnvelope(
                builder.build(), "/validation/kyc/v1/uan_basic_v3", Map.of())).isNotNull();

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).status()).isEqualTo(ProviderCall.SUCCESS);
        assertThat(recorded.get(0).httpStatus()).isEqualTo(200);
        assertThat(recorded.get(0).errorMessage()).isNull();
        server.verify();
    }

    /** The other half of the same rule: a caller that DOES reject the envelope gets a FAILED row. */
    @Test
    void aRejectedErrorEnvelopeIsRecordedAsFailedAndThrows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/validation/kyc/v1/pan_details_plus"))
                .andRespond(withSuccess("{\"status\":\"error\",\"message\":\"not verified\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> ProviderJson.post(
                builder.build(), "/validation/kyc/v1/pan_details_plus", Map.of()))
                .isInstanceOf(VerificationException.class);

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).failed()).isTrue();
        assertThat(recorded.get(0).httpStatus()).isEqualTo(200);
        server.verify();
    }

    /**
     * A {@code result_code} that is not 101 is not, on its own, a failure. This is the exact shape that
     * accounted for 1,337 phantom failures: Digitap answers HTTP 200 with a code the client reads as
     * "no record", which is an answer.
     */
    @Test
    void aResultCodeOtherThanOneZeroOneIsNotAFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/validation/kyc/v1/pan_details_plus"))
                .andRespond(withSuccess("{\"result_code\":102,\"message\":\"not verified\"}",
                        MediaType.APPLICATION_JSON));

        ProviderJson.post(builder.build(), "/validation/kyc/v1/pan_details_plus", Map.of());

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).status()).isEqualTo(ProviderCall.SUCCESS);
        assertThat(recorded.get(0).httpStatus()).isEqualTo(200);
        server.verify();
    }

    /**
     * The escape hatch for the narrow case the rule above cannot cover: the transport legitimately
     * recorded a served call, and the CLIENT then decided the envelope was a failure after all.
     * {@code FintrixCrifClient.rejectUnlessNoRecord} is the real caller.
     */
    @Test
    void failLastFlipsTheJustWrittenRowToFailed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/validation/kyc/v1/uan_basic_v3"))
                .andRespond(withSuccess("{\"result_code\":103}", MediaType.APPLICATION_JSON));

        ProviderJson.postAllowingErrorEnvelope(builder.build(), "/validation/kyc/v1/uan_basic_v3", Map.of());
        ProviderCallLog.failLast("client rejected the envelope");

        assertThat(reclassified).containsExactly("42:client rejected the envelope");
        server.verify();
    }

    /** No row was written (fixture mode, a NOOP recorder, a call that never left) — nothing to flip. */
    @Test
    void failLastIsANoOpWhenNoRowWasWritten() {
        ProviderCallContext.clear();
        ProviderCallLog.failLast("nothing to reclassify");
        assertThat(reclassified).isEmpty();
    }

    /**
     * {@code provider_api_execution.response_json} is {@code jsonb}, so an HTML error page or a bare
     * string fails the insert — and because recording is best-effort, that failure was swallowed and
     * the row vanished entirely. Wrapping keeps the evidence for the one row anybody would want to read.
     */
    @Test
    void aNonJsonBodyIsRecordedWrappedAndFailed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/pan"))
                .andRespond(withSuccess("<html><body>502 Bad Gateway</body></html>",
                        MediaType.TEXT_HTML));

        assertThatThrownBy(() -> ProviderJson.post(builder.build(), "/pan", Map.of()))
                .isInstanceOf(VerificationException.class);

        assertThat(recorded).hasSize(1);
        ProviderCall call = recorded.get(0);
        assertThat(call.failed()).isTrue();
        assertThat(call.responseJson()).contains("\"__unparseable\":true", "502 Bad Gateway");
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
