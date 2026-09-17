package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.verification.ProviderCallContext;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
import com.navix.verification.exception.VerificationException;
import com.navix.verification.support.ProviderCall;
import com.navix.verification.support.ProviderCallLog;
import com.navix.verification.support.ProviderCallRecorder;
import com.navix.verification.support.VendorEnvelopes;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    /**
     * The audit row is half of what this client is responsible for, so the tests capture it. A
     * recorder that logs both {@code record} and {@code markFailed} is the only way to tell a vendor
     * ANSWER wearing an error envelope (stays SUCCESS) from a genuine provider failure (flipped to
     * FAILED) — a distinction that went the wrong way for 1,939 of the 4,848 failure rows in the
     * Sep-2026 provider audit.
     */
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

    /**
     * Any OTHER error envelope is a genuine failure and must still throw so the chain falls through.
     *
     * <p>It must also LOOK like a failure on the Provider API dashboard. The transport wrote this row
     * SUCCESS on purpose — {@code postAllowingErrorEnvelope} tolerates an error-shaped body so a real
     * CRIF no-hit is not mistaken for a failure — which leaves the client, and only the client, able
     * to say that this particular envelope was not an answer. Without that explicit
     * {@code ProviderCallLog.failLast} an upstream bureau outage reads as a wall of healthy calls.
     */
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

        assertThat(recorded).hasSize(1);
        assertThat(reclassified).hasSize(1);
        assertThat(reclassified.get(0)).contains("Upstream bureau timeout");
    }

    /**
     * A missing score is NOT on its own a no-hit (see {@link #blankScoreValueIsNoHit} for the case where
     * it genuinely is). CRIF answered 47 applications in the Sep-2026 pending-queue audit with an
     * out-of-range {@code SCORE-VALUE} ("15") alongside a full {@code RESPONSES.RESPONSE} array of real
     * tradelines — {@link com.navix.verification.support.CrifHighmarkFactsParser#plausibleScore} nulls the
     * bogus score, but the report itself has substance
     * ({@link com.navix.verification.support.CrifHighmarkFactsParser#hasSubstance}) and must be kept, not
     * discarded as a thin file.
     */
    @Test
    void outOfRangeScoreIsDroppedButAReportWithSubstanceIsKept() {
        Bound b = bind();
        String populatedResponsesOutOfBandScore = """
                {"success":true,"canonical":{"data":{"name":"N","mobile":"9000000004",
                "credit_report":{"HEADER":{"REPORT-ID":"RID-15","DATE-OF-ISSUE":"01-01-2026"},
                "SCORES":{"SCORE":{"SCORE-VALUE":"15"}},
                "RESPONSES":{"RESPONSE":[{"LOAN-DETAILS":{"ACCT-NUMBER":"123456","CURRENT-BAL":"5000",
                "ACCOUNT-STATUS":"Active"}}]}}}}}
                """;
        b.server().expect(requestTo(BASE + "/crif_combine"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(populatedResponsesOutOfBandScore, MediaType.APPLICATION_JSON));

        CrifResponse r = new FintrixCrifClient(b.restClient(), new ObjectMapper(), "")
                .pull("N", "9000000004", "app-126");

        assertThat(r.noRecord()).isFalse();
        assertThat(r.score()).isNull();
        assertThat(r.facts()).isNotNull();
        b.server().verify();
    }

    /**
     * Fintrix answers a REJECTED REQUEST with HTTP 200 and an envelope that carries no {@code "status"}
     * key at all — {@code {"error":"Bad Request","message":"Missing required field name","success":true,
     * "statusCode":400}}. Without the {@code statusCode} check in {@code rejectUnlessNoRecord}, the
     * status-only guard reads a missing node, concludes "not an error", and falls into {@code parse()},
     * which finds no {@code credit_report} node and silently records the borrower as a thin file — 44
     * applications in the Sep-2026 pending-queue audit carried this exact envelope while CRIF had never
     * run a search at all.
     */
    @Test
    void rejectedRequestEnvelopeWithNoStatusFieldStillThrows() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + "/crif_combine"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"error\":\"Bad Request\",\"message\":\"Missing required field name\","
                                + "\"success\":true,\"statusCode\":400}",
                        MediaType.APPLICATION_JSON));

        FintrixCrifClient client = new FintrixCrifClient(b.restClient(), new ObjectMapper(), "");
        assertThatThrownBy(() -> client.pull("Sample Person", "9000000001", "app-123"))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("Missing required field name");

        // HTTP 200 means the transport recorded this SUCCESS; nothing but the client can know that
        // CRIF never ran a search. Those 44 applications were invisible on the dashboard precisely
        // because the row said the call had been served.
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).httpStatus()).isEqualTo(200);
        assertThat(reclassified).hasSize(1);
        assertThat(reclassified.get(0)).contains("statusCode 400");
        b.server().verify();
    }

    /**
     * The mirror image of the two tests above, on the real production body captured 2026-09-17: the
     * no-hit. CRIF answers a borrower it has never seen with HTTP 200 and the same error-shaped
     * envelope a failure wears, {@code "No data found in CRIF,Please re-verify details"} — and that is
     * a definitive ANSWER.
     *
     * <p>So this asserts both halves. The client must return a no-record rather than throw (a throw
     * burns a second billable call falling through to the next bureau, and FAILED is exactly what a
     * backfill re-run retries — so a borrower who can never hit would be paid for on every pass), and
     * the audit row must stay SUCCESS. Marking correct answers FAILED accounted for 1,939 of the
     * 4,848 failure rows in the Sep-2026 audit and took the dashboard's failure rate from ~10% to
     * 24.5%, which is what hid the outages that mattered.
     */
    @Test
    void aNoHitIsAnAnswerNotAFailure() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + "/crif_combine"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(VendorEnvelopes.load("fintrix-bureau-no-hit.json"),
                        MediaType.APPLICATION_JSON));

        CrifResponse r = new FintrixCrifClient(b.restClient(), new ObjectMapper(), "")
                .pull("Sample Person", "9000000001", "app-127");

        assertThat(r.noRecord()).isTrue();
        assertThat(r.score()).isNull();
        assertThat(r.facts()).isNull();
        assertThat(r.challenge()).isNull();

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).status()).isEqualTo(ProviderCall.SUCCESS);
        assertThat(recorded.get(0).errorMessage()).isNull();
        assertThat(reclassified).isEmpty();
        b.server().verify();
    }
}
