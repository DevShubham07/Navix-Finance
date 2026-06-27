package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.navix.verification.dto.FintrixDtos.AddressVerificationResponse;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
import com.navix.verification.dto.FintrixDtos.EmailVerificationResponse;
import com.navix.verification.dto.FintrixDtos.ExperianResponse;
import com.navix.verification.dto.FintrixDtos.FaceLivenessResponse;
import com.navix.verification.dto.FintrixDtos.PanResponse;
import com.navix.verification.dto.FintrixDtos.PennyDropResponse;
import com.navix.verification.exception.VerificationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Offline tests for the Fintrix clients: each binds a {@link MockRestServiceServer} to a fresh
 * {@link RestClient.Builder}, stubs the endpoint with the REAL provider envelope, and asserts the
 * mapped neutral DTO. The expected request URI also proves the relative endpoint join keeps the
 * {@code __api/api/v1/} base prefix.
 */
class FintrixClientsTest {

    private static final String BASE = "https://fintrix.test/__api/api/v1/";

    /** A bound (server, restClient) pair sharing one builder. */
    private record Bound(MockRestServiceServer server, RestClient restClient) {
    }

    private Bound bind() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Bound(server, builder.build());
    }

    private void stub(MockRestServiceServer server, String endpoint, String json) {
        server.expect(requestTo(BASE + endpoint))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void panComprehensiveMapsIdentity() {
        Bound b = bind();
        stub(b.server(), "pan_comprehensive", """
                {"status":"success","transaction_id":"TXN-PAN-1","data":{"status":"valid",
                "full_name":"  SHUBHAM","first_name":"","middle_name":"","last_name":"SHUBHAM",
                "dob":"2003-03-24","gender":"M","category":"person","aadhaar_linked":true,
                "masked_aadhaar":"65XXXXXXXX90","phone_number":"72XXXXXX66","email":"gs****96@gmail.com",
                "pan_number":"QVEPS0901K","address":{"full":"some addr","state":"Haryana","zip":"131001"}}}
                """);

        PanResponse r = new PanComprehensiveClient(b.restClient()).verify("QVEPS0901K", "navix-1-pan");

        assertThat(r.txnId()).isEqualTo("TXN-PAN-1");
        assertThat(r.status()).isEqualTo("valid");
        assertThat(r.fullName()).isEqualTo("SHUBHAM");
        assertThat(r.aadhaarLinked()).isTrue();
        assertThat(r.maskedAadhaar()).isEqualTo("65XXXXXXXX90");
        assertThat(r.panNumber()).isEqualTo("QVEPS0901K");
        assertThat(r.addressState()).isEqualTo("Haryana");
        assertThat(r.addressZip()).isEqualTo("131001");
        b.server().verify();
    }

    @Test
    void emailVerificationMapsSummary() {
        Bound b = bind();
        stub(b.server(), "cv_email_verification", """
                {"http_status_code":200,"result_code":101,"client_ref_num":"ref12345","request_id":"REQ-9fc2",
                "result":{"summary":{"is_verified":true,"is_email_valid":true,"is_establishment_matched":true,
                "is_individual_matched":true},
                "establishment_details":{"matched_establishments":[{"matched_establishment":
                "DIGITAP.AI ENTERPRISE SOLUTIONS PRIVATE LIMITED","est_id":"e1","source":"epfo","score":1}]},
                "individual_details":{"email_name":"ganesh.parmeshwar","is_individual_matched":true,"score":1},
                "additional_info":{"is_webmail":false,"is_generic_email":false}}}
                """);

        EmailVerificationResponse r = new EmailVerificationClient(b.restClient())
                .verify("a@b.com", "Ganesh", "Digitap", "navix-1-email");

        assertThat(r.txnId()).isEqualTo("REQ-9fc2");
        assertThat(r.resultCode()).isEqualTo(101);
        assertThat(r.isVerified()).isTrue();
        assertThat(r.isEmailValid()).isTrue();
        assertThat(r.isEstablishmentMatched()).isTrue();
        assertThat(r.isIndividualMatched()).isTrue();
        assertThat(r.isGenericEmail()).isFalse();
        assertThat(r.matchedEstablishment()).isEqualTo("DIGITAP.AI ENTERPRISE SOLUTIONS PRIVATE LIMITED");
        assertThat(r.individualScore()).isEqualTo(1.0);
        b.server().verify();
    }

    @Test
    void addressVerificationMapsModel() {
        Bound b = bind();
        stub(b.server(), "ent_address_verification", """
                {"code":"200","model":{"address":"K.S Corporate Tower India","pincode":"201301",
                "district":"Meerut Division","state":"Uttar Pradesh","country":"India","withInIndia":true}}
                """);

        AddressVerificationResponse r = new AddressVerificationClient(b.restClient())
                .verify(28.5, 77.3, "navix-1-address");

        assertThat(r.code()).isEqualTo("200");
        assertThat(r.state()).isEqualTo("Uttar Pradesh");
        assertThat(r.pincode()).isEqualTo("201301");
        assertThat(r.withinIndia()).isTrue();
        b.server().verify();
    }

    @Test
    void experianMapsThinFileAsNoRecord() {
        Bound b = bind();
        stub(b.server(), "individual_experian", """
                {"status":"success","transaction_id":"TXN-EXP-1","data":{"pan":"QVEPS0901K","credit_score":8,
                "client_id":"REQ1","credit_report":{"SCORE":{"FCIREXScore":8},"CAIS_Account":{}}},
                "message":"SYS100004 (No record found)"}
                """);

        ExperianResponse r = new ExperianClient(b.restClient()).pull("QVEPS0901K", "Shubham", "9999999999", "navix-1-exp");

        assertThat(r.txnId()).isEqualTo("TXN-EXP-1");
        assertThat(r.status()).isEqualTo("success");
        assertThat(r.creditScore()).isEqualTo(8);
        assertThat(r.noRecord()).isTrue();
        b.server().verify();
    }

    @Test
    void crifMapsStringNumbers() {
        Bound b = bind();
        stub(b.server(), "individual_crif", """
                {"status":"success","transaction_id":"TXN-CRIF-1","data":{"HEADER":{"STATUS":"SUCCESS"},
                "ACCOUNTS-SUMMARY":{"DERIVED-ATTRIBUTES":{"INQURIES-IN-LAST-SIX-MONTHS":"3"},
                "PRIMARY-ACCOUNTS-SUMMARY":{"PRIMARY-ACTIVE-NUMBER-OF-ACCOUNTS":"2",
                "PRIMARY-OVERDUE-NUMBER-OF-ACCOUNTS":"1","PRIMARY-CURRENT-BALANCE":"41000.50"}},
                "SCORES":{"SCORE":{"SCORE-VALUE":"742","SCORE-FACTORS":""}}}}
                """);

        CrifResponse r = new CrifClient(b.restClient())
                .pull("QVEPS0901K", "Shubham", "9999999999", "2003-03-24", "navix-1-crif");

        assertThat(r.txnId()).isEqualTo("TXN-CRIF-1");
        assertThat(r.headerStatus()).isEqualTo("SUCCESS");
        assertThat(r.score()).isEqualTo(742);
        assertThat(r.activeAccounts()).isEqualTo(2);
        assertThat(r.overdueAccounts()).isEqualTo(1);
        assertThat(r.totalBalance()).isEqualTo(41000.50);
        assertThat(r.enquiriesLast6m()).isEqualTo(3);
        b.server().verify();
    }

    @Test
    void crifBlankScoreParsesToNull() {
        Bound b = bind();
        stub(b.server(), "individual_crif", """
                {"status":"success","transaction_id":"TXN-CRIF-2","data":{"HEADER":{"STATUS":"SUCCESS"},
                "ACCOUNTS-SUMMARY":{"DERIVED-ATTRIBUTES":{"INQURIES-IN-LAST-SIX-MONTHS":"0"},
                "PRIMARY-ACCOUNTS-SUMMARY":{"PRIMARY-ACTIVE-NUMBER-OF-ACCOUNTS":"0",
                "PRIMARY-OVERDUE-NUMBER-OF-ACCOUNTS":"0","PRIMARY-CURRENT-BALANCE":"0"}},
                "SCORES":{"SCORE":{"SCORE-VALUE":"","SCORE-FACTORS":""}}}}
                """);

        CrifResponse r = new CrifClient(b.restClient())
                .pull("QVEPS0901K", "Shubham", "9999999999", "", "navix-1-crif");

        assertThat(r.score()).isNull();
        assertThat(r.activeAccounts()).isZero();
        b.server().verify();
    }

    @Test
    void pennyDropMapsAccountAndIfsc() {
        Bound b = bind();
        stub(b.server(), "verification_pennydrop", """
                {"status":"success","transaction_id":"TXN-PD-1","data":{"status":true,"account_exists":true,
                "full_name":"SHUBHAM","imps_ref_no":"617","ifsc_details":{"bank":"HDFC Bank",
                "branch":"KATHMANDI,SONIPAT","city":"SONIPAT","state":"HARYANA","ifsc":"HDFC0002557"}}}
                """);

        PennyDropResponse r = new PennyDropClient(b.restClient())
                .verify("00123456789", "HDFC0002557", "navix-1-pd");

        assertThat(r.txnId()).isEqualTo("TXN-PD-1");
        assertThat(r.status()).isTrue();
        assertThat(r.accountExists()).isTrue();
        assertThat(r.fullName()).isEqualTo("SHUBHAM");
        assertThat(r.ifscDetails()).isNotNull();
        assertThat(r.ifscDetails().bank()).isEqualTo("HDFC Bank");
        assertThat(r.ifscDetails().ifsc()).isEqualTo("HDFC0002557");
        b.server().verify();
    }

    @Test
    void faceLivenessMapsScores() {
        Bound b = bind();
        stub(b.server(), "vkyc_face_liveness", """
                {"status":"success","transaction_id":"TXN-FL-1","data":{"is_live":true,
                "is_person_image_blurry":false,"liveness_confidence":0.972,
                "person_image_correctly_identified":true,"multiple_face_detected":false,
                "is_face_occluded":false}}
                """);

        FaceLivenessResponse r = new FaceLivenessClient(b.restClient())
                .check("https://s3.example/selfie.jpg?sig=abc", "navix-1-liveness");

        assertThat(r.txnId()).isEqualTo("TXN-FL-1");
        assertThat(r.isLive()).isTrue();
        assertThat(r.livenessConfidence()).isEqualTo(0.972);
        assertThat(r.personImageCorrectlyIdentified()).isTrue();
        assertThat(r.multipleFaceDetected()).isFalse();
        b.server().verify();
    }

    @Test
    void serverErrorBecomesVerificationException() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + "pan_comprehensive"))
                .andRespond(withServerError());

        PanComprehensiveClient client = new PanComprehensiveClient(b.restClient());

        assertThatThrownBy(() -> client.verify("QVEPS0901K", "navix-1-pan"))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("pan_comprehensive");
        b.server().verify();
    }

    @Test
    void providerErrorStatusBecomesVerificationException() {
        Bound b = bind();
        stub(b.server(), "pan_comprehensive", """
                {"status":"error","message":"invalid id_number"}
                """);

        PanComprehensiveClient client = new PanComprehensiveClient(b.restClient());

        assertThatThrownBy(() -> client.verify("BAD", "navix-1-pan"))
                .isInstanceOf(VerificationException.class);
        b.server().verify();
    }
}
