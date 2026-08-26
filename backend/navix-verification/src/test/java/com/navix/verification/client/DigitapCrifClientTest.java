package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.navix.verification.dto.DigitapDtos.CrifResponse;
import com.navix.verification.exception.VerificationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Offline tests for {@link DigitapCrifClient} — the middle bureau leg. Covers the score's position in
 * the {@code B2C-REPORT} envelope (which is NOT where either other CRIF/Experian client looks), the
 * no-hit codes that must NOT throw, and a genuine failure that must.
 */
class DigitapCrifClientTest {

    private static final String BASE = "https://digitap.test";
    private static final String ENDPOINT = "/credit_analytics/v2/cf";

    private record Bound(MockRestServiceServer server, RestClient restClient) {
    }

    private Bound bind() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Bound(server, builder.build());
    }

    @Test
    void extractsScoreFromTheB2cReportEnvelope() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.mobile_no").value("9000000001"))
                .andExpect(jsonPath("$.prefill_lookup").value("0"))
                .andExpect(jsonPath("$.first_name").value("Pranav"))
                .andExpect(jsonPath("$.last_name").value("Chaudhari"))
                .andRespond(withSuccess("""
                        {"http_response_code":200,"client_ref_num":"TestClient",
                         "request_id":"REQ-CRIF-1","result_code":101,"message":"success",
                         "result":{"result_json":{"parsed_data":{"B2C-REPORT":{
                           "HEADER-SEGMENT":{"REPORT-ID":"CCR260727CR324997136","STATUS":"SUCCESS",
                                             "PRODUCT-TYPE":"BBC CONSUMER SCORE"},
                           "SCORE":[{"NAME":"PERFORM CONSUMER 2.2","VALUE":"510","DESCRIPTION":"M"}]
                         }}}}}
                        """, MediaType.APPLICATION_JSON));

        CrifResponse r = new DigitapCrifClient(b.restClient())
                .pull("ABCPH1234G", "Pranav Chaudhari", "9000000001", "19-09-2001", "app-1");

        assertThat(r.txnId()).isEqualTo("REQ-CRIF-1");
        assertThat(r.creditScore()).isEqualTo(510);
        assertThat(r.noRecord()).isFalse();
        assertThat(r.rawResponseJson()).contains("B2C-REPORT");
        b.server().verify();
    }

    /**
     * result_code 102 is the bureau saying "this person isn't in my file" — a real answer. It must come
     * back as noRecord rather than an exception, or the adapter falls through and burns a second
     * billable Experian pull on every thin-file borrower.
     */
    @Test
    void noRecordCodeIsReturnedNotThrown() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + ENDPOINT)).andRespond(withSuccess("""
                {"http_response_code":200,"client_ref_num":"test","request_id":"REQ-CRIF-2",
                 "result_code":102,"message":"No record found for the given input"}
                """, MediaType.APPLICATION_JSON));

        CrifResponse r = new DigitapCrifClient(b.restClient())
                .pull("ABCPH1234G", "Vishal Gowda", "9191934567", "01-01-1990", "app-2");

        assertThat(r.noRecord()).isTrue();
        assertThat(r.creditScore()).isNull();
        assertThat(r.txnId()).isEqualTo("REQ-CRIF-2");
    }

    /** 103 = name not found against the mobile. Also a real answer, also must not throw. */
    @Test
    void nameNotFoundCodeIsReturnedNotThrown() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + ENDPOINT)).andRespond(withSuccess("""
                {"http_response_code":200,"client_ref_num":"test","request_id":"REQ-CRIF-3",
                 "result_code":103,"message":"Name not found"}
                """, MediaType.APPLICATION_JSON));

        assertThat(new DigitapCrifClient(b.restClient())
                .pull("APFPM1234H", "Pavan Patel", "9191945678", "01-01-1990", "app-3").noRecord())
                .isTrue();
    }

    /** An unrecognised code is a genuine provider failure — throw so the router reaches Experian. */
    @Test
    void unrecognisedResultCodeThrows() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + ENDPOINT)).andRespond(withSuccess("""
                {"http_response_code":503,"client_ref_num":"test","request_id":"REQ-CRIF-4",
                 "result_code":503,"message":"Source is busy or unavailable. Try again later"}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new DigitapCrifClient(b.restClient())
                .pull("ADCPM1234H", "Hema Reddy", "9191023456", "01-01-1990", "app-4"))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("Source is busy");
    }

    /** A 101 with no score in the payload is a no-hit, not a crash. */
    @Test
    void successWithoutAScoreCountsAsNoRecord() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + ENDPOINT)).andRespond(withSuccess("""
                {"http_response_code":200,"request_id":"REQ-CRIF-5","result_code":101,
                 "result":{"result_json":{"parsed_data":{"B2C-REPORT":{"SCORE":[]}}}}}
                """, MediaType.APPLICATION_JSON));

        CrifResponse r = new DigitapCrifClient(b.restClient())
                .pull("ABCPH1234G", "Someone New", "9000000002", "01-01-1990", "app-5");

        assertThat(r.creditScore()).isNull();
        assertThat(r.noRecord()).isTrue();
    }
}
