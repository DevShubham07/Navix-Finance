package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
import com.navix.verification.exception.VerificationException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Offline tests for the KBA challenge half of {@link FintrixCrifClient}: carrying {@code report_id}
 * off a parked challenge, and {@code POST /bureau_ch_user_auth} — its verbatim answer encoding, its
 * FLATTER response envelope, its error handling, and the re-park case.
 */
class FintrixCrifChallengeTest {

    private static final String BASE = "https://fintrix.test";
    private static final String ANSWER_URL = BASE + "/bureau_ch_user_auth";

    private record Bound(MockRestServiceServer server, RestClient restClient) {
    }

    private Bound bind() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Bound(server, builder.build());
    }

    private FintrixCrifClient client(Bound b) {
        return new FintrixCrifClient(b.restClient(), new ObjectMapper(), "");
    }

    private static String answerFixture() throws Exception {
        try (InputStream in =
                     FintrixCrifChallengeTest.class.getResourceAsStream("/crif-auth-answer-sample.json")) {
            return new String(in.readAllBytes());
        }
    }

    /** The KBA envelope /crif_combine answers with when it withholds a report. */
    private static final String CHALLENGE_ENVELOPE = """
            {"status":"error","success":true,"timestamp":"2026-08-24T14:51:07.485Z",
             "transaction_id":"TXN-PROD-1",
             "data":{"options":["Galada Finance Ltd "," PAYU FINANCE INDIA PRIVATE LIMITED ",
                                " CREDILIO FINANCIAL TECH "," Sri Vijayaram Hire"],
                     "order_id":"txn-prod-1","question":"Which lender?",
                     "report_id":"CCR260824CR421737205","answer_type":"R"},
             "error_message":"Unable to Authenticate, Please Solve the Auth Questions",
             "request_id":"R-1"}
            """;

    /**
     * The regression that matters most. CRIF pads its option strings and compares them literally, so a
     * stray trim anywhere on the path turns a correct answer into a wrong one — which costs a billable
     * re-mint and looks, from the outside, like the borrower simply got it wrong.
     */
    @Test
    void answerIsSentVerbatimIncludingItsPaddingSpaces() throws Exception {
        Bound b = bind();
        b.server().expect(requestTo(ANSWER_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"remark":"app-1","order_id":"txn-prod-1","report_id":"CCR-1",
                         "auth_answers":" PAYU FINANCE INDIA PRIVATE LIMITED "}
                        """, true))
                .andRespond(withSuccess(answerFixture(), MediaType.APPLICATION_JSON));

        client(b).answerChallenge("txn-prod-1", "CCR-1", " PAYU FINANCE INDIA PRIVATE LIMITED ",
                "app-1", "Sample Person", "9000000001");

        b.server().verify();
    }

    /**
     * The answer envelope has NO {@code canonical} wrapper — {@code data} IS the credit_report node —
     * and no {@code credit_report_link}. It must still yield the same score/txnId/facts a wrapped
     * {@code /crif_combine} report does, because both feed the identical credit brief downstream.
     */
    @Test
    void flatAnswerEnvelopeParsesScoreTxnIdAndFacts() throws Exception {
        Bound b = bind();
        b.server().expect(requestTo(ANSWER_URL))
                .andRespond(withSuccess(answerFixture(), MediaType.APPLICATION_JSON));

        CrifResponse r = client(b).answerChallenge("txn-prod-1", "CCR-1", " SAMPLE ",
                "app-1", "Sample Person", "9000000001");

        assertThat(r.score()).isEqualTo(510);
        assertThat(r.noRecord()).isFalse();
        assertThat(r.txnId()).isEqualTo("CCR260824CR000000001");
        assertThat(r.creditReportLink()).isNull();
        assertThat(r.challenge()).isNull();
        assertThat(r.facts()).isNotNull();
        assertThat(r.facts().creditScore()).isEqualTo(510);
        // The raw envelope is kept as-is so the identity cross-check can read it back.
        assertThat(r.rawResponseJson()).doesNotContain("\"canonical\"");
        b.server().verify();
    }

    /** A wrong answer or a lapsed order comes back as an error envelope — that must not look like a
     *  thin file, or the borrower would be recorded as having no credit history. */
    @Test
    void rejectedAnswerThrowsRatherThanReadingAsNoRecord() {
        Bound b = bind();
        b.server().expect(requestTo(ANSWER_URL))
                .andRespond(withSuccess("""
                        {"status":"error","success":true,"error_message":"Invalid answer"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(b).answerChallenge("txn-prod-1", "CCR-1", " X ",
                "app-1", "N", "9000000001"))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("Invalid answer");
        b.server().verify();
    }

    /** CRIF can answer an answer with ANOTHER question. That re-parks; it is not a failure. */
    @Test
    void answerMayReturnAFreshChallenge() {
        Bound b = bind();
        b.server().expect(requestTo(ANSWER_URL))
                .andRespond(withSuccess(CHALLENGE_ENVELOPE, MediaType.APPLICATION_JSON));

        CrifResponse r = client(b).answerChallenge("txn-prod-0", "CCR-0", " X ",
                "app-1", "N", "9000000001");

        assertThat(r.challenge()).isNotNull();
        assertThat(r.challenge().orderId()).isEqualTo("txn-prod-1");
        assertThat(r.challenge().reportId()).isEqualTo("CCR260824CR421737205");
        assertThat(r.score()).isNull();
        assertThat(r.noRecord()).isFalse();
        b.server().verify();
    }

    /**
     * {@code report_id} used to be read into {@code txnId} and then dropped before it was ever
     * persisted, which left the first production cohort of parked challenges unanswerable. It must
     * reach the caller on the {@code Challenge} itself.
     */
    @Test
    void parkedChallengeCarriesBothOrderIdAndReportId() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + "/crif_combine"))
                .andRespond(withSuccess(CHALLENGE_ENVELOPE, MediaType.APPLICATION_JSON));

        CrifResponse r = client(b).pull("N", "9000000001", "app-1");

        assertThat(r.challenge()).isNotNull();
        assertThat(r.challenge().orderId()).isEqualTo("txn-prod-1");
        assertThat(r.challenge().reportId()).isEqualTo("CCR260824CR421737205");
        // The options keep their padding — the borrower has to send one back byte-for-byte.
        assertThat(r.challenge().options()).contains(" PAYU FINANCE INDIA PRIVATE LIMITED ");
        b.server().verify();
    }
}
