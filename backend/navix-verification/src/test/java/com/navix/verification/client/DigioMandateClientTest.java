package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.verification.MandatePort.MandateRequest;
import com.navix.common.verification.MandatePort.MandateResult;
import com.navix.verification.config.DigioProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Offline: every call goes to a {@link MockRestServiceServer}; nothing here can file a real mandate. */
@ExtendWith(OutputCaptureExtension.class)
class DigioMandateClientTest {

    private static final String BASE = "https://digio.test";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATED = """
            {"id":"ENA1","state":"partial","authentication_url":"https://app.digio.in/eDIGIO/DGO1"}""";

    private MockRestServiceServer server;

    private DigioMandateClient client() {
        RestClient.Builder b = RestClient.builder();
        server = MockRestServiceServer.bindTo(b).build();
        return new DigioMandateClient(
                new DigioProperties("digio", BASE, "id", "secret", "wh", "CFG1", null, 7), b);
    }

    private static MandateRequest req(long paise) {
        return new MandateRequest("CRN1", "Asha D'Souza-Rao", "9876543210", "123456789012", "PUNB0123400",
                "Punjab National Bank", paise, LocalDate.of(2026, 11, 1), null);
    }

    @Test
    void registerSendsFixedMandateParameters() throws Exception {
        DigioMandateClient c = client();
        AtomicReference<String> sent = new AtomicReference<>();
        server.expect(requestTo(BASE + "/v3/client/mandate/create_form"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic aWQ6c2VjcmV0"))
                .andExpect(r -> sent.set(((org.springframework.mock.http.client.MockClientHttpRequest) r).getBodyAsString()))
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        var session = c.register(req(2_000_000L));

        JsonNode body = JSON.readTree(sent.get());
        JsonNode data = body.path("mandate_data");
        assertThat(body.path("auth_mode").asText()).isEqualTo("API");
        assertThat(body.path("corporate_config_id").asText()).isEqualTo("CFG1");
        assertThat(data.path("management_category").asText()).isEqualTo("L001");
        assertThat(data.path("frequency").asText()).isEqualTo("Adhoc");
        assertThat(data.path("is_recurring").asBoolean()).isTrue();
        assertThat(data.path("maximum_amount").decimalValue()).isEqualByComparingTo("20000");
        assertThat(data.has("collection_amount")).isFalse();
        // Digio rejects apostrophes and hyphens in the name.
        assertThat(data.path("customer_name").asText()).isEqualTo("Asha D Souza Rao");
        assertThat(session.digioMandateId()).isEqualTo("ENA1");
        assertThat(session.authenticationUrl()).endsWith("DGO1");
        server.verify();
    }

    @Test
    void registerConvertsPaiseToRupeeDecimal() throws Exception {
        DigioMandateClient c = client();
        AtomicReference<String> sent = new AtomicReference<>();
        server.expect(requestTo(BASE + "/v3/client/mandate/create_form"))
                .andExpect(r -> sent.set(((org.springframework.mock.http.client.MockClientHttpRequest) r).getBodyAsString()))
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        c.register(req(123_456L));

        assertThat(JSON.readTree(sent.get()).path("mandate_data").path("maximum_amount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("1234.56"));
    }

    @Test
    void fetchParsesSnakeCaseAndUmrn() {
        DigioMandateClient c = client();
        server.expect(requestTo(BASE + "/v3/client/mandate/ENA1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"ENA1","state":"authentication_completed","auth_sub_mode":"debit",
                         "umrn":"YESB0000000000012345","some_new_field":1,
                         "bank_details":{"state":"registered","authenticated_at":"2026-10-16T10:15:30Z"}}""",
                        MediaType.APPLICATION_JSON));

        MandateResult r = c.fetch("ENA1");

        assertThat(r.state()).isEqualTo("authentication_completed");
        assertThat(r.bankState()).isEqualTo("registered");
        assertThat(r.umrn()).isEqualTo("YESB0000000000012345");
        assertThat(r.authSubMode()).isEqualTo("debit");
        assertThat(r.authenticatedAt()).isNotNull();
    }

    @Test
    void fetchReadsFrequencyAsAdho() {
        DigioMandateClient c = client();
        server.expect(requestTo(BASE + "/v3/client/mandate/ENA1"))
                .andRespond(withSuccess("""
                        {"id":"ENA1","state":"partial","mandate_details":{"frequency":"ADHO"}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(c.fetch("ENA1").digioMandateId()).isEqualTo("ENA1");
    }

    @Test
    void findByRefReturnsEmptyForNoMandates() {
        DigioMandateClient c = client();
        server.expect(requestTo(BASE + "/v3/client/mandate/by_crn/CRN1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(c.findByRef("CRN1")).isEmpty();
    }

    @Test
    void findByRefReturnsEmptyOn404() {
        DigioMandateClient c = client();
        server.expect(requestTo(BASE + "/v3/client/mandate/by_crn/CRN1"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"NOT_FOUND\"}"));

        assertThat(c.findByRef("CRN1")).isEmpty();
    }

    @Test
    void findByRefReturnsEmptyOnEmptyBody() {
        DigioMandateClient c = client();
        server.expect(requestTo(BASE + "/v3/client/mandate/by_crn/CRN1")).andRespond(withSuccess());

        assertThat(c.findByRef("CRN1")).isEmpty();
    }

    @Test
    void findByRefStillThrowsOnServerError() {
        DigioMandateClient c = client();
        server.expect(requestTo(BASE + "/v3/client/mandate/by_crn/CRN1"))
                .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"SYSTEM_ERROR\",\"message\":\"x\"}"));

        assertThatThrownBy(() -> c.findByRef("CRN1")).isInstanceOf(BusinessException.class);
    }

    @Test
    void httpFiveHundredSystemErrorThrowsBusinessExceptionNotRetryable(CapturedOutput out) {
        DigioMandateClient c = client();
        // A 500 is NOT transient here: exactly one request is expected, so a retry would fail verify().
        server.expect(requestTo(BASE + "/v3/client/mandate/create_form"))
                .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON).body("""
                        {"code":"SYSTEM_ERROR","message":"Not entitled","details":"EX261016211743769WZU"}"""));

        assertThatThrownBy(() -> c.register(req(1_000_000L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("SYSTEM_ERROR"))
                // Digio echoes request fields in its `message`, so it reaches neither the borrower...
                .hasMessageNotContaining("Not entitled");
        assertThat(out.getAll()).contains("EX261016211743769WZU");
        assertThat(out.getAll()).doesNotContain("Not entitled");   // ...nor a log line
        assertThat(out.getAll()).doesNotContain("123456789012");
        server.verify();
    }

    @Test
    void liveBanksParsesSnakeCaseCapabilities() {
        DigioMandateClient c = client();
        server.expect(requestTo(BASE + "/v3/client/mandate/banks?mode=API"))
                .andRespond(withSuccess("""
                        [{"id":"B1","name":"Punjab National Bank","ifsc_prefix":"PUNB","api_mandate":true,
                          "allowed_auth_sub_type":["aadhaar","debit"],"penny_less":true},
                         {"id":"B2","ifsc_prefix":"XXXX","api_mandate":false}]""",
                        MediaType.APPLICATION_JSON));

        var banks = c.liveBanks();

        assertThat(banks).hasSize(2);
        assertThat(banks.get(0).ifscPrefix()).isEqualTo("PUNB");
        assertThat(banks.get(0).apiMandate()).isTrue();
        assertThat(banks.get(0).allowedAuthSubTypes()).containsExactly("aadhaar", "debit");
        assertThat(banks.get(1).apiMandate()).isFalse();
    }

    private static final com.navix.common.verification.MandatePort.DebitRequest DEBIT =
            new com.navix.common.verification.MandatePort.DebitRequest("UMRN1", 1_270_000L,
                    LocalDate.of(2026, 11, 30), "L42-R1", "9999888877776666", "DhanBoost");

    @Test
    void presentDebitSendsAmountInPaiseAndAdhocFrequency() throws Exception {
        DigioMandateClient c = client();
        AtomicReference<String> sent = new AtomicReference<>();
        server.expect(requestTo(BASE + "/v3/client/nach_debit/scheduled/register"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(r -> sent.set(((org.springframework.mock.http.client.MockClientHttpRequest) r).getBodyAsString()))
                .andRespond(withSuccess("{\"id\":\"NSP1\"}", MediaType.APPLICATION_JSON));

        c.presentDebit(DEBIT);

        JsonNode b = JSON.readTree(sent.get());
        assertThat(b.path("amount_in_paise").isInt()).isTrue();
        assertThat(b.path("amount_in_paise").asInt()).isEqualTo(1_270_000);
        assertThat(b.has("amount")).isFalse();
        assertThat(b.path("frequency").asText()).isEqualTo("adhoc");
        assertThat(b.path("payment_count").asInt()).isEqualTo(1);
        assertThat(b.path("umrn").asText()).isEqualTo("UMRN1");
        assertThat(b.path("unique_key").asText()).isEqualTo("L42-R1");
        assertThat(b.path("settlement_date").asText()).isEqualTo("2026-11-30");
        server.verify();
    }

    @Test
    void presentDebitParsesPlanAndTransactionId() {
        DigioMandateClient c = client();
        server.expect(requestTo(BASE + "/v3/client/nach_debit/scheduled/register"))
                .andRespond(withSuccess("""
                        {"id":"NSP1","status":"active","upcoming_transaction":{"id":"NPT1","state":"scheduled",
                        "scheduled_settlement_date":"2026-12-01 00:00:00"}}""", MediaType.APPLICATION_JSON));

        var plan = c.presentDebit(DEBIT);

        assertThat(plan.planId()).isEqualTo("NSP1");
        assertThat(plan.status()).isEqualTo("active");
        assertThat(plan.txnId()).isEqualTo("NPT1");
        assertThat(plan.scheduledSettlementDate()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void debitStatusParsesUtrAndFailureCode() {
        DigioMandateClient c = client();
        server.expect(requestTo(BASE + "/v3/client/nach_debit/transaction/NPT1"))
                .andRespond(withSuccess("""
                        {"id":"NPT1","details":{"status":"FAILED","amount_in_paise":1270000,"utr":"U9",
                        "failure_code":"51","failure_reason":"Insufficient funds"}}""", MediaType.APPLICATION_JSON));

        var o = c.debitStatus("NPT1");

        assertThat(o.status()).isEqualTo("FAILED");
        assertThat(o.utr()).isEqualTo("U9");
        assertThat(o.failureCode()).isEqualTo("51");
        assertThat(o.failureReason()).isEqualTo("Insufficient funds");
        assertThat(o.amountPaise()).isEqualTo(1_270_000L);
    }

    @Test
    void debitBusinessErrorOnFiveHundredIsNotRetryable(CapturedOutput out) {
        DigioMandateClient c = client();
        server.expect(requestTo(BASE + "/v3/client/nach_debit/scheduled/register"))
                .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON).body("""
                        {"code":"SYSTEM_ERROR","message":"acct 9999888877776666","details":"EX1"}"""));

        assertThatThrownBy(() -> c.presentDebit(DEBIT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("debit").hasMessageNotContaining("mandate setup")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("SYSTEM_ERROR"));
        assertThat(out.getAll()).contains("EX1").doesNotContain("9999888877776666");
        server.verify();
    }

    @Test
    void cancelDebitReturnsFalseWhenAlreadyInitiated() {
        DigioMandateClient c = client();
        server.expect(requestTo(BASE + "/v3/client/nach_debit/transaction/action"))
                .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"SYSTEM_ERROR\",\"message\":\"x\",\"details\":\"EX2\"}"));

        assertThat(c.cancelDebit("NPT1")).isFalse();
    }

    @Test
    void cancelMandateReturnsFalseWhenNoUmrnYet() {
        DigioMandateClient c = client();
        server.expect(requestTo(BASE + "/v3/client/mandate/registration_cancel"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON).body("""
                        {"code":"BAD_REQUEST","message":"Cancellation not allowed, Umrn not yet available","details":"EX4"}"""));

        assertThat(c.cancelMandate("ENA1", null, "HDFC0001234", "C002")).isFalse();
        server.verify();
    }

    private static final String BY_REF =BASE + "/v3/client/nach_debit/scheduled/by_reference_id/L42-R1";

    @Test
    void findDebitByRefReturnsEmptyOnNotFound() {
        DigioMandateClient c = client();
        server.expect(requestTo(BY_REF)).andRespond(
                org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound());
        assertThat(c.findDebitByRef("L42-R1")).isEmpty();
        server.verify();
    }

    @Test
    void findDebitByRefParsesPlanAndTransactionId() {
        DigioMandateClient c = client();
        server.expect(requestTo(BY_REF)).andRespond(withSuccess("""
                {"id":"NSP1","status":"active","last_transaction":{"id":"NPT9"},
                 "upcoming_transaction":{"state":"scheduled","scheduled_settlement_date":"2026-12-01 00:00:00"}}""",
                MediaType.APPLICATION_JSON));

        var plan = c.findDebitByRef("L42-R1").orElseThrow();

        assertThat(plan.planId()).isEqualTo("NSP1");
        assertThat(plan.txnId()).isEqualTo("NPT9");
        assertThat(plan.scheduledSettlementDate()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void findDebitByRefThrowsOnOtherErrors() {
        DigioMandateClient c = client();
        server.expect(requestTo(BY_REF)).andRespond(withServerError().contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"SYSTEM_ERROR\",\"details\":\"EX3\"}"));
        assertThatThrownBy(() -> c.findDebitByRef("L42-R1")).isInstanceOf(BusinessException.class);
    }

    @Test
    void findDebitByRefThrowsOnEmptyOrUnparseableSuccess() {
        for (String body : new String[] {"", "not json", "{}", "[]"}) {
            DigioMandateClient c = client();
            server.expect(requestTo(BY_REF)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> c.findDebitByRef("L42-R1"))
                    .as("body '%s' must not read as 'no debit'", body)
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void presentDebitDoesNotTakeTxnIdFromLastTransaction() {
        DigioMandateClient c = client();
        server.expect(requestTo(BASE + "/v3/client/nach_debit/scheduled/register")).andRespond(withSuccess("""
                {"id":"NSP1","status":"active","last_transaction":{"id":"NPT_OLD"}}""", MediaType.APPLICATION_JSON));
        assertThat(c.presentDebit(DEBIT).txnId()).isNull();
    }
}
