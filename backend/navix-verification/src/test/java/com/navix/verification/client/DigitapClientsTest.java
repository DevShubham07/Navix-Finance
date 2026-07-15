package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.navix.verification.dto.DigitapDtos.AddressResponse;
import com.navix.verification.dto.DigitapDtos.CreditResponse;
import com.navix.verification.dto.DigitapDtos.EmailResponse;
import com.navix.verification.dto.DigitapDtos.FaceMatchResponse;
import com.navix.verification.dto.DigitapDtos.PanResponse;
import com.navix.verification.exception.VerificationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Offline tests for the Digitap clients: bind a {@link MockRestServiceServer} to a fresh
 * {@link RestClient.Builder}, stub the endpoint with a representative Digitap envelope (from
 * {@code docs/digitap/digitap-apis.json}), and assert the mapped DTO.
 */
class DigitapClientsTest {

    private static final String BASE = "https://digitap.test";

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
    void panDetailsPlusMapsIdentity() {
        Bound b = bind();
        stub(b.server(), "/validation/kyc/v1/pan_details_plus", """
                {"http_response_code":200,"result_code":101,"request_id":"REQ-PAN-1","result":{
                "pan":"ABCPE1234Z","fullname":"  JOHN DOE","first_name":"JOHN","last_name":"DOE",
                "gender":"male","dob":"11/08/1990","aadhaar_linked":true,"pan_status":"VALID",
                "address":{"state":"Haryana","pincode":"131001"}}}
                """);

        PanResponse r = new DigitapPanClient(b.restClient()).verify("ABCPE1234Z", "ref-1");

        assertThat(r.txnId()).isEqualTo("REQ-PAN-1");
        assertThat(r.valid()).isTrue();
        assertThat(r.fullName()).isEqualTo("JOHN DOE");
        assertThat(r.dob()).isEqualTo("11/08/1990");
        assertThat(r.aadhaarLinked()).isTrue();
        assertThat(r.addressState()).isEqualTo("Haryana");
        assertThat(r.addressZip()).isEqualTo("131001");
        b.server().verify();
    }

    @Test
    void emailVerificationMapsSummary() {
        Bound b = bind();
        stub(b.server(), "/cv/email_verification/v1", """
                {"http_status_code":200,"result_code":101,"request_id":"REQ-EM-1","result":{
                "summary":{"is_verified":true,"is_email_valid":true,"is_establishment_matched":true,
                "is_individual_matched":true},
                "establishment_details":{"matched_establishments":[{"matched_establishment":"DIGITAP.AI"}]},
                "individual_details":{"score":1},"additional_info":{"is_generic_email":false}}}
                """);

        EmailResponse r = new DigitapEmailClient(b.restClient())
                .verify("a@b.com", "John", "Digitap", "ref-1");

        assertThat(r.txnId()).isEqualTo("REQ-EM-1");
        assertThat(r.resultCode()).isEqualTo(101);
        assertThat(r.isVerified()).isTrue();
        assertThat(r.isEstablishmentMatched()).isTrue();
        assertThat(r.isGenericEmail()).isFalse();
        assertThat(r.matchedEstablishment()).isEqualTo("DIGITAP.AI");
        assertThat(r.individualScore()).isEqualTo(1.0);
        b.server().verify();
    }

    @Test
    void addressMapsModel() {
        Bound b = bind();
        stub(b.server(), "/ent/v1/address-verification", """
                {"code":"200","model":{"address":"K.S Corporate Tower","pincode":"201301",
                "district":"Gautam Buddha Nagar","state":"Uttar Pradesh","country":"India","withInIndia":true}}
                """);

        AddressResponse r = new DigitapAddressClient(b.restClient()).verify(28.5, 77.3, "ref-1");

        assertThat(r.code()).isEqualTo("200");
        assertThat(r.state()).isEqualTo("Uttar Pradesh");
        assertThat(r.pincode()).isEqualTo("201301");
        assertThat(r.withinIndia()).isTrue();
        b.server().verify();
    }

    @Test
    void faceMatchMapsResult() {
        Bound b = bind();
        stub(b.server(), "/fmfl/v2/face-match", """
                {"status":"success","statusCode":"200","clientRefId":"ref-1","reqId":"REQ-FM-1",
                "result":{"is_same_face":true,"same_face_confidence":0.9998,"is_person_image_blurry":false}}
                """);

        FaceMatchResponse r = new DigitapFaceMatchClient(b.restClient())
                .match("https://s3/selfie.jpg", null, "ref-1");

        assertThat(r.txnId()).isEqualTo("REQ-FM-1");
        assertThat(r.sameFace()).isTrue();
        assertThat(r.confidence()).isEqualTo(0.9998);
        assertThat(r.personImageBlurry()).isFalse();
        b.server().verify();
    }

    @Test
    void creditMapsBureauScore() {
        Bound b = bind();
        stub(b.server(), "/credit_analytics/request", """
                {"http_response_code":200,"result_code":101,"request_id":"REQ-CR-1","result":{
                "result_json":{"INProfileResponse":{
                "CreditProfileHeader":{"ReportNumber":"RPT-9"},
                "SCORE":{"BureauScore":"800"},
                "CAIS_Account":{"CAIS_Summary":{"Credit_Account":{"CreditAccountActive":"2"},
                "Total_Outstanding_Balance":{"Outstanding_Balance_All":"50000"}}},
                "TotalCAPS_Summary":{"TotalCAPSLast30Days":"0"}}}}}
                """);

        CreditResponse r = new DigitapCreditClient(b.restClient())
                .pull("ABCPE1234Z", "John Doe", "9999999999", "1990-01-01", "ref-1");

        assertThat(r.txnId()).isEqualTo("REQ-CR-1");
        assertThat(r.creditScore()).isEqualTo(800);
        assertThat(r.noRecord()).isFalse();
        assertThat(r.facts()).isNotNull();
        assertThat(r.facts().activeAccounts()).isEqualTo(2);
        b.server().verify();
    }

    @Test
    void serverErrorBecomesVerificationException() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + "/validation/kyc/v1/pan_details_plus"))
                .andRespond(withServerError());

        DigitapPanClient client = new DigitapPanClient(b.restClient());
        assertThatThrownBy(() -> client.verify("BAD", "ref-1"))
                .isInstanceOf(VerificationException.class);
        b.server().verify();
    }
}
