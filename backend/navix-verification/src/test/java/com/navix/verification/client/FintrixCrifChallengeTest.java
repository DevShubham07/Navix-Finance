package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.verification.ProviderCallContext;
import com.navix.common.verification.ProviderFailureDetails;
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

    /**
     * Whether a given envelope leaves the audit row SUCCESS or flips it to FAILED is part of the
     * behaviour under test here, not incidental plumbing: 1,939 of the 4,848 failure rows in the
     * Sep-2026 provider audit were correct vendor ANSWERS logged as failures, which took the
     * dashboard's apparent failure rate from ~10% to 24.5% and buried the outages worth looking at.
     * So the recorder captures both halves — {@code record} and {@code markFailed}.
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

    /**
     * The exact envelope that lost applications 9741 and 9474 their reports. {@code
     * /bureau_ch_user_auth} does not reply like {@code /crif_combine}: its {@code error_message} is a
     * JSON <b>object</b>, and {@code status "S11"} means "your answer was taken, here is the NEXT
     * question" — with a fresh {@code orderId}/{@code reportId}. Because the string-shaped
     * {@link FintrixCrifClient} challenge branch could not see an object, this fell through to the
     * generic error path, the consumer kept the OLD question on screen, the borrower answered a
     * question CRIF had already replaced, and CRIF scored it wrong. Both applications walked
     * S11 &rarr; S11 &rarr; S02 in twelve seconds and the report was gone after three billed attempts.
     *
     * <p>The options are asserted with their leading/trailing spaces intact. CRIF pads them and
     * compares the answer literally, and the consumer also checks the borrower's pick is a member of
     * this list — a trim anywhere on that path turns a correct answer into a wrong one, which is
     * indistinguishable from the borrower guessing and costs a billable re-mint.
     */
    @Test
    void answerEnvelopeS11IsAFreshChallengeWithTheNewQuestion() {
        Bound b = bind();
        b.server().expect(requestTo(ANSWER_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(VendorEnvelopes.load("fintrix-answer-s11.json"),
                        MediaType.APPLICATION_JSON));

        CrifResponse r = client(b).answerChallenge("txn-test-00000000-1111-2222-3333-444444444444",
                "CCR260916CR000000001", " EXAMPLE BANK ", "app-1", "Sample Person", "9000000001");

        assertThat(r.challenge()).isNotNull();
        assertThat(r.challenge().question())
                .isEqualTo("Please Choose a Bank Name OR Institution Name for the latest Loan taken");
        assertThat(r.challenge().options()).containsExactly(
                "EXAMPLE FINANCIAL TECH ",
                " EXAMPLE BANK ",
                " EXAMPLE CREDIT SERVICES LIMITED ",
                " Example Hire Purchase");
        assertThat(r.challenge().orderId()).isEqualTo("txn-test-00000000-1111-2222-3333-444444444444");
        assertThat(r.challenge().reportId()).isEqualTo("CCR260916CR000000001");
        assertThat(r.txnId()).isEqualTo("CCR260916CR000000001");
        // A gated report is NOT a thin file — CRIF handed back a report id, it is just withheld.
        assertThat(r.noRecord()).isFalse();
        assertThat(r.score()).isNull();
        b.server().verify();
    }

    /**
     * The end of the same twelve-second sequence: {@code status "S02"},
     * {@code "Authentication failed due to unsuccesfull all ans attempt failed"} (CRIF's spelling) —
     * every answer attempt the bureau allows has been spent. Answering that order id again is
     * billable and can only fail, so it must surface as {@link ProviderFailureDetails#KBA_EXHAUSTED}
     * rather than as the generic "unspecified provider error" the object envelope used to produce:
     * {@code navix-loan} stores that code verbatim, and it is what tells staff to re-mint the pull
     * instead of asking the borrower to try once more.
     */
    @Test
    void answerEnvelopeS02IsKbaExhausted() {
        Bound b = bind();
        b.server().expect(requestTo(ANSWER_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(VendorEnvelopes.load("fintrix-answer-s02.json"),
                        MediaType.APPLICATION_JSON));

        Throwable thrown = catchThrowable(() -> client(b).answerChallenge(
                "txn-test-00000000-1111-2222-3333-444444444444", "CCR260916CR000000001",
                " EXAMPLE BANK ", "app-1", "Sample Person", "9000000001"));

        assertThat(thrown).isInstanceOf(VerificationException.class);
        VerificationException failure = (VerificationException) thrown;
        assertThat(failure.providerCode()).isEqualTo(ProviderFailureDetails.KBA_EXHAUSTED);
        assertThat(failure.safeDetail()).contains("ans attempt failed");
        assertThat(failure.endpoint()).isEqualTo("/bureau_ch_user_auth");
        // The audit row stays SUCCESS. S02 is CRIF ANSWERING over a perfectly healthy HTTP 200 —
        // "this order is closed" — and nothing about the call went wrong. Filing it FAILED is the
        // same mistake that put 1,939 of 4,848 correct answers in the failure column and took the
        // dashboard's apparent failure rate from ~10% to 24.5%. The borrower's outcome is recorded on
        // application_verification, which is where a reviewer looks for it. Compare
        // answerEnvelopeWithAnUnknownStatusStillThrowsAndFailsTheRow below, where the row IS flipped
        // because we genuinely could not read what the vendor said.
        assertThat(reclassified).isEmpty();
        b.server().verify();
    }

    /**
     * Guards the blast radius of the object-envelope branch. Its two known statuses are S11 and S02;
     * an object carrying anything else is a shape nobody has seen, and Fintrix publishes no document
     * for this endpoint that would let us guess. It must throw rather than be read as a challenge (a
     * challenge with no question parks the file forever) or as a thin file (which would record a
     * borrower who has a credit history as having none).
     *
     * <p>And unlike the S02 case the row genuinely is a failure, so it must be reclassified: the
     * transport already wrote it SUCCESS, because {@code postAllowingErrorEnvelope} tolerates an
     * error-shaped body so a real no-hit is not mistaken for a failure. Without the explicit
     * {@code failLast} an unparseable vendor reply would sit on the Provider API dashboard as a
     * healthy call while the borrower's bureau pull went to manual review.
     */
    @Test
    void answerEnvelopeWithAnUnknownStatusStillThrowsAndFailsTheRow() {
        Bound b = bind();
        b.server().expect(requestTo(ANSWER_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"status":"error","success":true,
                         "error_message":{"status":"S77","orderId":"txn-test-0000",
                                          "reportId":"CCR260916CR000000002",
                                          "statusDesc":"Order reference is no longer valid"},
                         "transaction_id":"TXN-TEST-1"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(b).answerChallenge("txn-test-0000", "CCR260916CR000000002",
                " EXAMPLE BANK ", "app-1", "Sample Person", "9000000001"))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("unrecognised answer envelope")
                .hasMessageContaining("S77");

        assertThat(recorded).hasSize(1);
        assertThat(reclassified).hasSize(1);
        assertThat(reclassified.get(0)).contains("unrecognised answer envelope");
        b.server().verify();
    }
}
