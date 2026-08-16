package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.navix.verification.dto.DigitapDtos.UanAdvancedResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Offline tests for {@link DigitapUanAdvancedClient} — the UAN Advanced V4 employment lookup.
 *
 * <p>The envelopes below are taken from the vendor's UAN-Advanced-V4 sample in
 * {@code docs/digitap/digitap-apis.json}. The cases that matter are the non-101 ones: 103 and 104
 * arrive as HTTP <b>200</b>, so treating the HTTP status as the outcome would silently report
 * "no EPFO record" as a successful employment confirmation.
 */
class DigitapUanAdvancedClientTest {

    private static final String BASE = "https://digitap.test";
    private static final String ENDPOINT = "/cv/v4/uan_advanced/sync";

    private record Bound(MockRestServiceServer server, RestClient restClient) {
    }

    private Bound bind() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Bound(server, builder.build());
    }

    private void stub(MockRestServiceServer server, String json) {
        server.expect(requestTo(BASE + ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void resolvedRecordMapsEmploymentAndMatchFlags() {
        Bound b = bind();
        stub(b.server(), """
                {"http_response_code":200,"client_ref_num":"ref-1","request_id":"REQ-UAN-1",
                "result_code":101,"result":{
                  "uan":["100080045558"],
                  "summary":{
                    "recent_employer_data":{
                      "member_id":"BGBNG002680251403","establishment_id":"BGBNG00268",
                      "date_of_exit":"","date_of_joining":"2021-10-18",
                      "establishment_name":"SPRINKLR INDIA PVT LTD",
                      "employer_confidence_score":0.92,"matching_uan":"100080045558",
                      "epfo":{"is_recent":true,"is_name_unique":true,"has_pf_filings_details":true}},
                    "is_employed":true,"employee_name_match":true,"employer_name_match":true,
                    "uan_count":1,"date_of_exit_marked":false},
                  "uan_details":{"100080045558":{"basic_details":{
                    "gender":"MALE","date_of_birth":"2002-09-02","name":"KARTIK JINDAL",
                    "mobile":"","aadhaar_verification_status":1}}}}}
                """);

        UanAdvancedResponse r = new DigitapUanAdvancedClient(b.restClient())
                .verify("BXFPJ0767C", "9999999999", "2002-09-02", "Kartik Jindal", "Sprinklr", "ref-1");

        assertThat(r.txnId()).isEqualTo("REQ-UAN-1");
        assertThat(r.resultCode()).isEqualTo(101);
        assertThat(r.isEmployed()).isTrue();
        assertThat(r.uan()).isEqualTo("100080045558");
        assertThat(r.uanCount()).isEqualTo(1);
        assertThat(r.employerName()).isEqualTo("SPRINKLR INDIA PVT LTD");
        assertThat(r.establishmentId()).isEqualTo("BGBNG00268");
        assertThat(r.dateOfJoining()).isEqualTo("2021-10-18");
        // An empty date_of_exit means "still employed" and must normalise to null, not "".
        assertThat(r.dateOfExit()).isNull();
        assertThat(r.employeeNameMatch()).isTrue();
        assertThat(r.employerNameMatch()).isTrue();
        assertThat(r.employerConfidenceScore()).isEqualTo(0.92);
        assertThat(r.isRecent()).isTrue();
        assertThat(r.hasPfFilings()).isTrue();
        assertThat(r.nameOnRecord()).isEqualTo("KARTIK JINDAL");
        assertThat(r.dobOnRecord()).isEqualTo("2002-09-02");
        assertThat(r.genderOnRecord()).isEqualTo("MALE");
        b.server().verify();
    }

    @Test
    void noRecordFoundIsNotAnEmploymentConfirmation() {
        Bound b = bind();
        // result_code 103 on an HTTP 200 — the trap this test exists to catch.
        stub(b.server(), """
                {"http_response_code":200,"request_id":"REQ-UAN-2","client_ref_num":"ref-2",
                 "result_code":103,"message":"No record(s) found"}
                """);

        UanAdvancedResponse r = new DigitapUanAdvancedClient(b.restClient())
                .verify("BXFPJ0767C", null, null, null, null, "ref-2");

        assertThat(r.resultCode()).isEqualTo(103);
        assertThat(r.message()).isEqualTo("No record(s) found");
        assertThat(r.isEmployed()).isNull();
        assertThat(r.uan()).isNull();
        assertThat(r.employerName()).isNull();
        b.server().verify();
    }

    @Test
    void tooManyUansResolvesNothing() {
        Bound b = bind();
        stub(b.server(), """
                {"http_response_code":200,"request_id":"REQ-UAN-3","client_ref_num":"ref-3",
                 "result_code":104,"message":"Too many responses"}
                """);

        UanAdvancedResponse r = new DigitapUanAdvancedClient(b.restClient())
                .verify(null, "9999999999", null, null, null, "ref-3");

        assertThat(r.resultCode()).isEqualTo(104);
        assertThat(r.message()).isEqualTo("Too many responses");
        assertThat(r.isEmployed()).isNull();
        b.server().verify();
    }

    @Test
    void employerNameIsDroppedWhenEmployeeNameIsMissing() {
        Bound b = bind();
        // The provider rejects employer_name unless employee_name rides along, so the client must not
        // send it alone — and must omit unset identifiers entirely rather than sending JSON nulls.
        b.server().expect(requestTo(BASE + ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.pan").value("BXFPJ0767C"))
                .andExpect(jsonPath("$.employer_name").doesNotExist())
                .andExpect(jsonPath("$.employee_name").doesNotExist())
                .andExpect(jsonPath("$.mobile").doesNotExist())
                .andRespond(withSuccess("""
                        {"http_response_code":200,"request_id":"REQ-UAN-4","result_code":103,
                         "message":"No record(s) found"}
                        """, MediaType.APPLICATION_JSON));

        new DigitapUanAdvancedClient(b.restClient())
                .verify("BXFPJ0767C", null, null, null, "Sprinklr", "ref-4");

        b.server().verify();
    }

    @Test
    void fallsBackToTheUanArrayWhenMatchingUanIsAbsent() {
        Bound b = bind();
        stub(b.server(), """
                {"http_response_code":200,"request_id":"REQ-UAN-5","result_code":101,"result":{
                  "uan":["100548426123"],
                  "summary":{"recent_employer_data":{"establishment_name":"ABP NETWORK PRIVATE LIMITED",
                    "date_of_joining":"2015-04-01","date_of_exit":"",
                    "epfo":{"is_recent":true}},"is_employed":true,"uan_count":1},
                  "uan_details":{"100548426123":{"basic_details":{"name":"SUBHASHISH NAYAK"}}}}}
                """);

        UanAdvancedResponse r = new DigitapUanAdvancedClient(b.restClient())
                .verify(null, "9999999999", null, null, null, "ref-5");

        assertThat(r.uan()).isEqualTo("100548426123");
        assertThat(r.nameOnRecord()).isEqualTo("SUBHASHISH NAYAK");
        b.server().verify();
    }

    @Test
    void v4EmploymentHistoryAndPartialOutputAreIgnoredWithoutDisturbingTheV3Mapping() {
        // Verbatim-shaped V4 envelope (docs/digitap/digitap-apis.json, uan-advanced-v4 200 sample):
        // employment_history[], partial_output, epfo_details and uan_source are V4-only and
        // deliberately unmapped — they must not shift or break any field the EMPLOYMENT step already
        // reads.
        Bound b = bind();
        stub(b.server(), """
                {"http_response_code":200,"client_ref_num":"ref-6","request_id":"REQ-UAN-6",
                "result_code":101,"partial_output":false,"uan_source":["input"],"result":{
                  "uan":["102028758928"],
                  "summary":{
                    "recent_employer_data":{
                      "member_id":"MH/BAN/1234567/000/1234567","establishment_id":"MHBAN1234567000",
                      "date_of_exit":"","date_of_joining":"2023-08-07",
                      "establishment_name":"NAMRA FINANCE LIMITED",
                      "employer_confidence_score":1.0,"matching_uan":"102028758928",
                      "epfo":{"is_recent":true,"is_name_unique":true,"has_pf_filings_details":true}},
                    "is_employed":true,"employee_name_match":true,"employer_name_match":true,
                    "uan_count":1,"date_of_exit_marked":false},
                  "uan_details":{"102028758928":{
                    "basic_details":{"gender":"MALE","date_of_birth":"1995-01-01","name":"VIKASH",
                      "mobile":"","aadhaar_verification_status":1},
                    "employment_details":{"establishment_name":"NAMRA FINANCE LIMITED"},
                    "additional_details":{"bank_account":"","relative_name":""},
                    "employment_history":[{"establishment_name":"NAMRA FINANCE LIMITED",
                      "date_of_joining":"2023-08-07","date_of_exit":"","employment_period_in_months":18,
                      "is_recent":true,"is_employed":true,"matched_name":"VIKASH","is_name_exact":true}]}},
                  "epfo_details":{"matches":[],"pf_filing_details":[],"establishment_info":{}}}}
                """);

        UanAdvancedResponse r = new DigitapUanAdvancedClient(b.restClient())
                .verify("BXFPJ0767C", "9999999999", "1995-01-01", "Vikash", "Namra Finance", "ref-6");

        assertThat(r.employerName()).isEqualTo("NAMRA FINANCE LIMITED");
        assertThat(r.uan()).isEqualTo("102028758928");
        assertThat(r.dateOfExit()).isNull();
        assertThat(r.hasPfFilings()).isTrue();
        assertThat(r.employerConfidenceScore()).isEqualTo(1.0);
        assertThat(r.nameOnRecord()).isEqualTo("VIKASH");
        assertThat(r.genderOnRecord()).isEqualTo("MALE");
        b.server().verify();
    }
}
