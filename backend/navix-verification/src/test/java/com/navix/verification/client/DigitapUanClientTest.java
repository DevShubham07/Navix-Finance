package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.navix.verification.dto.DigitapDtos.UanLookupResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Offline tests for {@link DigitapUanClient} — the EPFO/UAN employment lookup.
 *
 * <p>The envelopes below are shaped after the vendor's UAN-Basic-V3 entry in
 * {@code docs/digitap/digitap-apis.json} ({@code /apis/27}) — the one variant provisioned on our
 * account. The cases that matter are the non-101 ones: 103 and 104 arrive as HTTP <b>200</b>, so
 * treating the HTTP status as the outcome would silently report "no EPFO record" as a successful
 * employment confirmation.
 *
 * <p>Every identity here is synthetic. A real UAN response carries PAN, mobile, DOB, gender and an
 * unmasked employer, and none of that belongs in a fixture.
 */
class DigitapUanClientTest {

    private static final String BASE = "https://digitap.test";
    private static final String ENDPOINT = "/cv/v3/uan_basic/sync";

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
                "mobile":"9000000000","employee_name":"FIRSTNAME LASTNAME","employer_name":null,
                "result_code":101,"result":{
                  "uan":["100000000000"],
                  "summary":{
                    "recent_employer_data":{
                      "member_id":"XXXXX00000000000000000","establishment_id":"XXXXX0000000000",
                      "date_of_exit":"","date_of_joining":"2024-07-29","leave_reason":"",
                      "establishment_name":"EXAMPLE EMPLOYER PVT LTD",
                      "employer_confidence_score":null,"matching_uan":"100000000000"},
                    "matching_uan":"100000000000",
                    "is_employed":true,"employee_name_match":true,"employer_name_match":true,
                    "uan_count":1,"date_of_exit_marked":false},
                  "uan_details":{"100000000000":{"basic_details":{
                    "gender":"MALE","date_of_birth":"2000-01-31","employee_confidence_score":1.0,
                    "name":"FIRSTNAME LASTNAME","mobile":"","aadhaar_verification_status":1}}},
                  "uan_source":[{"uan":"100000000000","source":"pan and mobile"}],
                  "name_dob_filtering_score":null}}
                """);

        UanLookupResponse r = new DigitapUanClient(b.restClient())
                .verify("AAAPA0000A", "9000000000", "2000-01-31", "Firstname Lastname", "Example Employer", "ref-1");

        assertThat(r.txnId()).isEqualTo("REQ-UAN-1");
        assertThat(r.resultCode()).isEqualTo(101);
        assertThat(r.isEmployed()).isTrue();
        assertThat(r.uan()).isEqualTo("100000000000");
        assertThat(r.uanCount()).isEqualTo(1);
        assertThat(r.employerName()).isEqualTo("EXAMPLE EMPLOYER PVT LTD");
        assertThat(r.establishmentId()).isEqualTo("XXXXX0000000000");
        assertThat(r.dateOfJoining()).isEqualTo("2024-07-29");
        // An empty date_of_exit means "still employed" and must normalise to null, not "".
        assertThat(r.dateOfExit()).isNull();
        assertThat(r.dateOfExitMarked()).isFalse();
        // An empty leave_reason is the same story as date_of_exit.
        assertThat(r.leaveReason()).isNull();
        assertThat(r.uanSource()).isEqualTo("pan and mobile");
        assertThat(r.employeeNameMatch()).isTrue();
        assertThat(r.employerNameMatch()).isTrue();
        assertThat(r.nameOnRecord()).isEqualTo("FIRSTNAME LASTNAME");
        assertThat(r.dobOnRecord()).isEqualTo("2000-01-31");
        assertThat(r.genderOnRecord()).isEqualTo("MALE");
        b.server().verify();
    }

    @Test
    void basicVariantCarriesNoPfCrossCheckAndSaysSoWithNullNotFalse() {
        // The whole cost of being on Basic V3 rather than Advanced V4: there is no `epfo` node and no
        // employer_confidence_score. These must read as "not carried by this product" (null), never as
        // a negative finding — a false here would tell a reviewer the PF filings had been checked and
        // come back bad, which is the opposite of the truth.
        Bound b = bind();
        stub(b.server(), """
                {"http_response_code":200,"request_id":"REQ-UAN-2","result_code":101,"result":{
                  "uan":["100000000001"],
                  "summary":{"recent_employer_data":{"establishment_name":"EXAMPLE EMPLOYER PVT LTD",
                    "date_of_joining":"2024-07-29","date_of_exit":""},
                    "matching_uan":"100000000001","is_employed":true,"uan_count":1},
                  "uan_details":{"100000000001":{"basic_details":{"name":"FIRSTNAME LASTNAME"}}}}}
                """);

        UanLookupResponse r = new DigitapUanClient(b.restClient())
                .verify("AAAPA0000A", null, null, null, null, "ref-2");

        assertThat(r.isEmployed()).isTrue();
        assertThat(r.isRecent()).isNull();
        assertThat(r.hasPfFilings()).isNull();
        assertThat(r.employerConfidenceScore()).isNull();
        assertThat(r.dateOfExitMarked()).isNull();
        assertThat(r.uanSource()).isNull();
        b.server().verify();
    }

    @Test
    void noRecordFoundIsNotAnEmploymentConfirmation() {
        Bound b = bind();
        // result_code 103 on an HTTP 200 — the trap this test exists to catch. A first job, a cash
        // employer and a non-PF establishment all land here legitimately.
        stub(b.server(), """
                {"http_response_code":200,"request_id":"REQ-UAN-3","client_ref_num":"ref-3",
                 "result_code":103,"message":"No record(s) found"}
                """);

        UanLookupResponse r = new DigitapUanClient(b.restClient())
                .verify("AAAPA0000A", null, null, null, null, "ref-3");

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
                {"http_response_code":200,"request_id":"REQ-UAN-4","client_ref_num":"ref-4",
                 "result_code":104,"message":"Too many responses"}
                """);

        UanLookupResponse r = new DigitapUanClient(b.restClient())
                .verify(null, "9000000000", null, null, null, "ref-4");

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
                .andExpect(jsonPath("$.pan").value("AAAPA0000A"))
                .andExpect(jsonPath("$.employer_name").doesNotExist())
                .andExpect(jsonPath("$.employee_name").doesNotExist())
                .andExpect(jsonPath("$.mobile").doesNotExist())
                .andRespond(withSuccess("""
                        {"http_response_code":200,"request_id":"REQ-UAN-5","result_code":103,
                         "message":"No record(s) found"}
                        """, MediaType.APPLICATION_JSON));

        new DigitapUanClient(b.restClient())
                .verify("AAAPA0000A", null, null, null, "Example Employer", "ref-5");

        b.server().verify();
    }

    @Test
    void fallsBackToTheUanArrayWhenMatchingUanIsAbsent() {
        Bound b = bind();
        stub(b.server(), """
                {"http_response_code":200,"request_id":"REQ-UAN-6","result_code":101,"result":{
                  "uan":["100000000002"],
                  "summary":{"recent_employer_data":{"establishment_name":"ANOTHER EMPLOYER LIMITED",
                    "date_of_joining":"2015-04-01","date_of_exit":""},
                    "is_employed":true,"uan_count":1},
                  "uan_details":{"100000000002":{"basic_details":{"name":"SECOND PERSON"}}}}}
                """);

        UanLookupResponse r = new DigitapUanClient(b.restClient())
                .verify(null, "9000000000", null, null, null, "ref-6");

        assertThat(r.uan()).isEqualTo("100000000002");
        assertThat(r.nameOnRecord()).isEqualTo("SECOND PERSON");
        b.server().verify();
    }

    @Test
    void anExitedEmploymentReportsTheExitDateAndReason() {
        // The other outcome that changes a credit decision: EPFO has a record, but the borrower has
        // left. `is_employed` false plus a real date_of_exit is what the EMPLOYMENT step turns into a
        // REVIEW rather than a PASS.
        Bound b = bind();
        stub(b.server(), """
                {"http_response_code":200,"request_id":"REQ-UAN-7","result_code":101,"result":{
                  "uan":["100000000003"],
                  "summary":{"recent_employer_data":{"establishment_name":"FORMER EMPLOYER LIMITED",
                    "establishment_id":"YYYYY0000000000","date_of_joining":"2021-02-01",
                    "date_of_exit":"2026-03-31","leave_reason":"RESIGNATION"},
                    "matching_uan":"100000000003","is_employed":false,"uan_count":1,
                    "date_of_exit_marked":true},
                  "uan_details":{"100000000003":{"basic_details":{"name":"THIRD PERSON"}}}}}
                """);

        UanLookupResponse r = new DigitapUanClient(b.restClient())
                .verify("AAAPA0000A", null, null, null, null, "ref-7");

        assertThat(r.isEmployed()).isFalse();
        assertThat(r.dateOfExit()).isEqualTo("2026-03-31");
        assertThat(r.dateOfExitMarked()).isTrue();
        assertThat(r.leaveReason()).isEqualTo("RESIGNATION");
        assertThat(r.employerName()).isEqualTo("FORMER EMPLOYER LIMITED");
        b.server().verify();
    }
}
