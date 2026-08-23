package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
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
}
