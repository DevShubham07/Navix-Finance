package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
import com.navix.verification.exception.VerificationException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Offline tests for {@link FintrixCrifClient}: the live envelope unwrap ({@code canonical.data}), the
 * score/txnId/link extraction, the defensive no-hit branch, and fixture mode (the same
 * {@code navix.bureau.fixture} property {@code SignzyExperianClient} honours).
 */
class FintrixCrifClientTest {

    private static final String BASE = "https://fintrix.test";

    private record Bound(MockRestServiceServer server, RestClient restClient) {
    }

    private Bound bind() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Bound(server, builder.build());
    }

    private static String fixtureJson() throws Exception {
        try (InputStream in = FintrixCrifClientTest.class.getResourceAsStream("/crif-combine-sample.json")) {
            return new String(in.readAllBytes());
        }
    }

    @Test
    void liveEnvelopeUnwrapsScoreTxnIdAndLink() throws Exception {
        Bound b = bind();
        b.server().expect(requestTo(BASE + "/crif_combine"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixtureJson(), MediaType.APPLICATION_JSON));

        CrifResponse r = new FintrixCrifClient(b.restClient(), new ObjectMapper(), "")
                .pull("Sample Person", "9000000001", "app-123");

        assertThat(r.score()).isEqualTo(799);
        assertThat(r.noRecord()).isFalse();
        assertThat(r.txnId()).isEqualTo("CCR260822CR415935862");
        assertThat(r.creditReportLink()).contains("&amp;");
        assertThat(r.facts()).isNotNull();
        assertThat(r.facts().creditScore()).isEqualTo(799);
        assertThat(r.rawResponseJson()).contains("\"canonical\"");
        b.server().verify();
    }

    @Test
    void fixtureModeNeverCallsTheProviderAndServesTheBundledCrifSample() throws Exception {
        Bound b = bind();
        // The configured path's VALUE is irrelevant here — it is only an on/off toggle for Fintrix
        // (Experian and CRIF are different shapes), so this always serves the bundled
        // crif-combine-sample.json regardless of what "classpath:samplepan.json" (an Experian-shaped
        // fixture) names. No stub registered — a real call would fail verify(); fixture mode must never
        // post.
        CrifResponse r = new FintrixCrifClient(b.restClient(), new ObjectMapper(), "classpath:samplepan.json")
                .pull("Sample Person", "9000000001", "app-123");

        assertThat(r.score()).isEqualTo(799);
        assertThat(r.facts()).isNotNull();
        b.server().verify();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void missingCreditReportIsNoHitAndLogsOnce(CapturedOutput output) {
        Bound b = bind();
        String noHitEnvelope = """
                {"success":true,"canonical":{"timestamp":"2026-08-22T00:00:00Z",
                "transaction_id":"TXN-1","status":"success","data":{"name":"N","mobile":"9000000002"}},
                "is_sandbox":false,"request_id":"R-1","transaction_id":"TXN-1"}
                """;
        b.server().expect(requestTo(BASE + "/crif_combine"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(noHitEnvelope, MediaType.APPLICATION_JSON));

        CrifResponse r = new FintrixCrifClient(b.restClient(), new ObjectMapper(), "")
                .pull("N", "9000000002", "app-124");

        assertThat(r.noRecord()).isTrue();
        assertThat(r.score()).isNull();
        assertThat(r.facts()).isNull();
        assertThat(output).contains("Fintrix crif_combine no-hit branch fired for the first time");
        b.server().verify();
    }

    @Test
    void blankScoreValueIsNoHit() {
        Bound b = bind();
        String blankScore = """
                {"success":true,"canonical":{"data":{"name":"N","mobile":"9000000003",
                "credit_report":{"HEADER":{"REPORT-ID":"RID-1"},"SCORES":{"SCORE":{"SCORE-VALUE":""}}}}}}
                """;
        b.server().expect(requestTo(BASE + "/crif_combine"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(blankScore, MediaType.APPLICATION_JSON));

        CrifResponse r = new FintrixCrifClient(b.restClient(), new ObjectMapper(), "")
                .pull("N", "9000000003", "app-125");

        assertThat(r.noRecord()).isTrue();
        assertThat(r.score()).isNull();
        b.server().verify();
    }

    /**
     * The production no-hit shape, captured 2026-08-23 on the first backfill batch: HTTP 200 with an
     * ERROR envelope whose message says no data. It is a real "this person has no CRIF record" answer,
     * so it must come back as a no-record rather than throwing — otherwise the router burns a second
     * billable call falling through to Digitap, the backfill records FAILED, and FAILED is exactly what
     * a re-run retries, so a borrower who can never hit is paid for on every pass.
     */
    @Test
    void noDataFoundErrorEnvelopeIsANoRecordNotAFailure() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + "/crif_combine"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"error\",\"success\":true,"
                        + "\"error_message\":\"No data found in CRIF,Please re-verify details\","
                        + "\"transaction_id\":\"TXN-PROD-x\"}", MediaType.APPLICATION_JSON));

        CrifResponse r = new FintrixCrifClient(b.restClient(), new ObjectMapper(), "")
                .pull("Sample Person", "9000000001", "app-123");

        assertThat(r.noRecord()).isTrue();
        assertThat(r.score()).isNull();
        assertThat(r.facts()).isNull();
        b.server().verify();
    }

    /**
     * CRIF can gate a REAL, existing report (note {@code report_id}) behind a knowledge-based-auth
     * question instead of a failure. Must not throw (a throw burns a second billable Digitap call) and
     * must not come back as {@code noRecord} (that would misclassify a real report as a thin file).
     */
    @Test
    void kbaChallengeEnvelopeReturnsAChallengeAndDoesNotThrow() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + "/crif_combine"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"error\",\"success\":true,"
                        + "\"error_message\":\"Unable to Authenticate, Please Solve the Auth Questions\","
                        + "\"data\":{\"question\":\"Please choose Disbursed Amount range for the latest "
                        + "Loan taken\",\"options\":[\"0-5k \",\" 5k-20k \",\" 5lac-10lac \",\"1lac-5lac\"],"
                        + "\"order_id\":\"txn-prod-b92e0254-3577-44c5-a71c-7a2b79df62b7\","
                        + "\"report_id\":\"CCR260823CR417431688\",\"answer_type\":\"R\"}}",
                        MediaType.APPLICATION_JSON));

        CrifResponse r = new FintrixCrifClient(b.restClient(), new ObjectMapper(), "")
                .pull("Sample Person", "9000000001", "app-123");

        assertThat(r.noRecord()).isFalse();
        assertThat(r.score()).isNull();
        assertThat(r.txnId()).isEqualTo("CCR260823CR417431688");
        assertThat(r.challenge()).isNotNull();
        assertThat(r.challenge().orderId()).isEqualTo("txn-prod-b92e0254-3577-44c5-a71c-7a2b79df62b7");
        assertThat(r.challenge().question()).contains("Disbursed Amount range");
        assertThat(r.challenge().options()).hasSize(4);
        b.server().verify();
    }

    /** Any OTHER error envelope is a genuine failure and must still throw so the chain falls through. */
    @Test
    void anUnrecognisedErrorEnvelopeStillThrows() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + "/crif_combine"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"error\",\"success\":false,"
                        + "\"error_message\":\"Upstream bureau timeout\"}", MediaType.APPLICATION_JSON));

        FintrixCrifClient client = new FintrixCrifClient(b.restClient(), new ObjectMapper(), "");
        assertThatThrownBy(() -> client.pull("Sample Person", "9000000001", "app-123"))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("Upstream bureau timeout");
    }
}
