package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.verification.dto.SignzyDtos.AadhaarResponse;
import com.navix.verification.dto.SignzyDtos.BankVerificationResponse;
import com.navix.verification.dto.SignzyDtos.CrifResponse;
import com.navix.verification.dto.SignzyDtos.DigiLockerSession;
import com.navix.verification.dto.SignzyDtos.EmailV2Response;
import com.navix.verification.dto.SignzyDtos.ExperianResponse;
import com.navix.verification.dto.SignzyDtos.LivenessResult;
import com.navix.verification.dto.SignzyDtos.LivenessSession;
import com.navix.verification.dto.SignzyDtos.PanResponse;
import com.navix.verification.exception.VerificationException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Offline tests for the Signzy clients: each binds a {@link MockRestServiceServer} to a fresh
 * {@link RestClient.Builder}, stubs the endpoint with a representative Signzy envelope (from
 * {@code docs/signzy/signzy-apis.json}), and asserts the mapped DTO.
 */
class SignzyClientsTest {

    private static final String BASE = "https://signzy.test";

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
    void bankVerificationMapsActiveAndName() {
        Bound b = bind();
        stub(b.server(), "/api/v3/bankaccountverification/pennydrop-v1", """
                {"result":{"active":"yes","reason":"success","nameMatch":"yes",
                "signzyReferenceId":"SGZ-REF-1","auditTrail":{"nature":"BANK RRN","value":"334912557776"},
                "bankTransfer":{"response":"Transaction Successful","beneName":"RAVI KUMAR","beneIFSC":"KKBK0008066"}}}
                """);

        BankVerificationResponse r = new SignzyBankVerificationClient(b.restClient())
                .verify("00123456789", "KKBK0008066", "Ravi Kumar");

        assertThat(r.txnId()).isEqualTo("SGZ-REF-1");
        assertThat(r.active()).isTrue();
        assertThat(r.beneName()).isEqualTo("RAVI KUMAR");
        assertThat(r.beneIfsc()).isEqualTo("KKBK0008066");
        b.server().verify();
    }

    @Test
    void emailV2MapsDeliverabilityAndEnrichment() {
        Bound b = bind();
        // Booleans arrive as JSON strings; enrichment lives under personalDetails/companyDetails.
        stub(b.server(), "/api/v3/email/verificationV2", """
                {"result":{"emailId":"kartik@sprinklr.com","validEmail":"true","status":"valid",
                "freeEmail":"false","didYouMean":"","domain":"sprinklr.com","mxFound":"true",
                "mxRecord":"sprinklr-com.mail.protection.outlook.com","smtpProvider":"microsoft",
                "personalDetails":{"name":"KARTIK JINDAL"},"companyDetails":{"name":"SPRINKLR"}}}
                """);

        EmailV2Response r = new SignzyEmailClient(b.restClient()).verify("kartik@sprinklr.com");

        assertThat(r.validEmail()).isTrue();
        assertThat(r.status()).isEqualTo("valid");
        assertThat(r.freeEmail()).isFalse();
        assertThat(r.mxFound()).isTrue();
        assertThat(r.smtpProvider()).isEqualTo("microsoft");
        assertThat(r.personName()).isEqualTo("KARTIK JINDAL");
        assertThat(r.companyName()).isEqualTo("SPRINKLR");
        b.server().verify();
    }

    @Test
    void pan206abMapsTopLevelFields() {
        Bound b = bind();
        stub(b.server(), "/api/v3/pan/compliance-206-individual-search", """
                {"number":"ABCPE1234Z","entityName":"  FXXL NXXE","unMaskedName":"  FIRST NAME",
                "panAllotmentDate":"20-07-2010",
                "panAadhaarLinkStatus":"linked","compliant":"true","isSpecified":"No","panStatus":"operative"}
                """);

        PanResponse r = new SignzyPanClient(b.restClient()).verify("ABCPE1234Z");

        assertThat(r.number()).isEqualTo("ABCPE1234Z");
        assertThat(r.entityName()).isEqualTo("FXXL NXXE");
        assertThat(r.unMaskedName()).isEqualTo("FIRST NAME");
        assertThat(r.panAadhaarLinkStatus()).isEqualTo("linked");
        assertThat(r.isSpecified()).isEqualTo("No");
        assertThat(r.panStatus()).isEqualTo("operative");
        b.server().verify();
    }

    @Test
    void reverseGeocodeMapsAddress() {
        Bound b = bind();
        stub(b.server(), "/api/v3/geocoding/reverse-geocode", """
                {"address":"RXJ8+22, VALUKKUPARAI, TAMIL NADU 641032, INDIA","latitude":"10.83","longitude":"76.96",
                "confidenceScore":0.7,"city":"VALUKKUPARAI","state":"TAMIL NADU","stateCode":"TN",
                "country":"INDIA","countryCode":"IN","zipcode":"641032"}
                """);

        var r = new SignzyGeocodeClient(b.restClient()).reverseGeocode(10.83, 76.96);

        assertThat(r.address()).contains("VALUKKUPARAI");
        assertThat(r.state()).isEqualTo("TAMIL NADU");
        assertThat(r.zipcode()).isEqualTo("641032");
        assertThat(r.countryCode()).isEqualTo("IN");
        b.server().verify();
    }

    @Test
    void experianMapsScoreAndFacts() {
        Bound b = bind();
        stub(b.server(), "/api/v3/bureau/experian-lite", """
                {"statusCode":200,"message":"SUCCESS","data":{"jsonExperianReport":{
                "CreditProfileHeader":{"ReportNumber":"1712213567495","Version":"V2.4"},
                "SCORE":{"FCIREXScore":742},
                "CAIS_Account":{"CAIS_Summary":{"Credit_Account":{"CreditAccountTotal":"5",
                "CreditAccountActive":"3","CreditAccountClosed":"2","CreditAccountDefault":"0"},
                "Total_Outstanding_Balance":{"Outstanding_Balance_All":"120000"}}},
                "TotalCAPS_Summary":{"TotalCAPSLast30Days":"1"}}}}
                """);

        ExperianResponse r = new SignzyExperianClient(b.restClient(), new ObjectMapper(), "", "3.109.169.131")
                .pull("ABCPE1234Z", "Ravi Kumar", "9999999999", "1990-01-01");

        assertThat(r.txnId()).isEqualTo("1712213567495");
        assertThat(r.creditScore()).isEqualTo(742);
        assertThat(r.noRecord()).isFalse();
        assertThat(r.facts()).isNotNull();
        assertThat(r.facts().activeAccounts()).isEqualTo(3);
        assertThat(r.facts().totalBalanceRupees()).isEqualTo(120000L);
        assertThat(r.rawResponseJson()).contains("\"jsonExperianReport\"", "\"ReportNumber\":\"1712213567495\"");
        b.server().verify();
    }

    @Test
    void crifMapsScore() {
        Bound b = bind();
        stub(b.server(), "/api/v3/bureau/crif", """
                {"statusCode":200,"message":"success","data":{"crifReport":{"INDV-REPORT-FILE":{
                "INDV-REPORTS":[{"INDV-REPORT":{"SCORES":[{"SCORE-VALUE":"690","SCORE-FACTORS":"SF02|"}]}}]}}}}
                """);

        CrifResponse r = new SignzyCrifClient(b.restClient())
                .pull("ABCPE1234Z", "Ravi Kumar", "9999999999", "1990-01-01");

        assertThat(r.score()).isEqualTo(690);
        assertThat(r.noRecord()).isFalse();
        assertThat(r.rawResponseJson()).contains("\"crifReport\"", "\"SCORE-FACTORS\":\"SF02|\"");
        b.server().verify();
    }

    @Test
    void livenessCreateUrlMapsSession() {
        Bound b = bind();
        stub(b.server(), "/api/v3/liveness-secure/createUrl", """
                {"token":"TKN-1","consumerId":"CID-1",
                "videoUrl":"https://liveliness.signzy.app/consumer/CID-1/token/TKN-1"}
                """);

        LivenessSession s = new SignzyLivenessClient(b.restClient())
                .createUrl("https://s3/presigned-aadhaar.jpg");

        assertThat(s.token()).isEqualTo("TKN-1");
        assertThat(s.consumerId()).isEqualTo("CID-1");
        assertThat(s.videoUrl()).contains("/token/TKN-1");
        b.server().verify();
    }

    @Test
    void livenessCreateUrlRequestOmitsNullMatchImage() throws Exception {
        // Signzy 400s on "matchImage":null ("must be an array") — it must be absent for liveness-only.
        String withNull = new ObjectMapper().writeValueAsString(
                new com.navix.verification.dto.SignzyDtos.LivenessCreateUrlRequest(null, 0.6));
        assertThat(withNull).doesNotContain("matchImage");

        String withImage = new ObjectMapper().writeValueAsString(
                new com.navix.verification.dto.SignzyDtos.LivenessCreateUrlRequest(
                        java.util.List.of("https://ref.jpg"), 0.6));
        assertThat(withImage).contains("matchImage");
    }

    @Test
    void livenessGetDataMapsResult() {
        Bound b = bind();
        stub(b.server(), "/api/v3/liveness-secure/getData", """
                {"result":{"token":"TKN-1","isUsed":1,"passiveLiveliness":{"liveness":true,"score":0.97},
                "faceMatch":{"verified":true,"matchPercentage":"98.00%"},
                "capturedImage":"https://persist.signzy.tech/selfie.jpg","status":true}}
                """);

        LivenessResult r = new SignzyLivenessClient(b.restClient()).getData("TKN-1");

        assertThat(r.completed()).isTrue();
        assertThat(r.txnId()).isEqualTo("TKN-1");
        assertThat(r.live()).isTrue();
        assertThat(r.livenessScore()).isEqualTo(0.97);
        assertThat(r.faceVerified()).isTrue();
        assertThat(r.matchPercentage()).isEqualTo("98.00%");
        assertThat(r.capturedImage()).contains("selfie.jpg");
        assertThat(r.overallStatus()).isTrue();
        b.server().verify();
    }

    @Test
    void livenessGetDataReturnsPendingOn404() {
        Bound b = bind();
        // Signzy returns 404 "Video Verification is not completed till now" until the borrower finishes.
        b.server().expect(requestTo(BASE + "/api/v3/liveness-secure/getData"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"error\":{\"message\":\"Video Verification is not completed till now\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        LivenessResult r = new SignzyLivenessClient(b.restClient()).getData("TKN-1");

        assertThat(r.completed()).isFalse();
        assertThat(r.txnId()).isEqualTo("TKN-1");
        assertThat(r.live()).isNull();
        b.server().verify();
    }

    @Test
    void digilockerCreateUrlAndEAadhaar() {
        Bound b = bind();
        stub(b.server(), "/api/v3/digilocker-v2/createUrl", """
                {"result":{"url":"https://api.digitallocker.gov.in/.../authorize?state=REQ-1","requestId":"REQ-1"}}
                """);
        DigiLockerSession s = new SignzyDigiLockerClient(b.restClient())
                .createUrl("https://navix.app/kyc/callback?app=1&sid=n", true);
        assertThat(s.requestId()).isEqualTo("REQ-1");
        assertThat(s.url()).contains("authorize");
        b.server().verify();

        Bound b2 = bind();
        stub(b2.server(), "/api/v3/digilocker-v2/geteAadhaar", """
                {"result":{"name":"  NAME","uid":"xxxxxxxx0353","dob":"01/01/1990","gender":"FEMALE",
                "x509Data":{"validAadhaarDSC":"yes","subjectName":"DS NATIONAL E-GOVERNANCE DIVISION 1"},"address":"addr",
                "splitAddress":{"district":["THANJAVUR"],"state":[["TAMIL NADU","TN"]],"city":["KUMBAKONAM"],
                "pincode":"612001","country":["IN","IND","INDIA"],"addressLine":"Address","landMark":"KUMBAKONAM"},
                "photo":"https://persist.signzy.tech/p.jpeg","aadhaarPdf":"https://persist.signzy.tech/a.pdf",
                "aadhaarJpeg":"https://persist.signzy.tech/a.jpeg","eAadhaarXmlLink":"https://persist.signzy.tech/a.xml"}}
                """);
        AadhaarResponse a = new SignzyDigiLockerClient(b2.restClient()).getEAadhaar("REQ-1");
        assertThat(a.fullName()).isEqualTo("NAME");
        assertThat(a.maskedUid()).isEqualTo("xxxxxxxx0353");
        assertThat(a.validDsc()).isTrue();
        assertThat(a.state()).isEqualTo("TAMIL NADU");
        assertThat(a.district()).isEqualTo("THANJAVUR");
        assertThat(a.city()).isEqualTo("KUMBAKONAM");
        assertThat(a.country()).isEqualTo("INDIA");
        assertThat(a.addressLine()).isEqualTo("Address");
        assertThat(a.landmark()).isEqualTo("KUMBAKONAM");
        assertThat(a.dscSubject()).isEqualTo("DS NATIONAL E-GOVERNANCE DIVISION 1");
        assertThat(a.pdfUrl()).endsWith(".pdf");
        assertThat(a.jpegUrl()).endsWith(".jpeg");
        assertThat(a.xmlUrl()).endsWith(".xml");
        b2.server().verify();
    }

    @Test
    void eAadhaarResponseRetainsAdminSafeDocumentAndAddressFields() {
        var fields = Arrays.stream(AadhaarResponse.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertThat(fields).contains("district", "city", "country", "addressLine", "landmark",
                "dscSubject", "pdfUrl", "jpegUrl");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void panErrorLogsTemporaryRawRequestAndResponse(CapturedOutput output) {
        Bound b = bind();
        b.server().expect(requestTo(BASE + "/api/v3/pan/compliance-206-individual-search"))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"PAN ABCPE1234Z is not entitled\"}}"));

        SignzyPanClient client = new SignzyPanClient(b.restClient());
        assertThatThrownBy(() -> client.verify("ABCPE1234Z"))
                .isInstanceOf(VerificationException.class);
        assertThat(output).contains(
                "TEMP_PII_DEBUG",
                "provider=signzy-pan",
                "requestPayload={\"panNumber\":\"ABCPE1234Z\",\"maskedName\":\"false\"}",
                "responsePayload={\"error\":{\"message\":\"PAN ABCPE1234Z is not entitled\"}}");
        b.server().verify();
    }
}
