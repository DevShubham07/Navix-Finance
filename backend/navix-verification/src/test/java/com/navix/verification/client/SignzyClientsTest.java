package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.verification.dto.SignzyDtos.AadhaarResponse;
import com.navix.verification.dto.SignzyDtos.BankVerificationResponse;
import com.navix.verification.dto.SignzyDtos.CrifResponse;
import com.navix.verification.dto.SignzyDtos.DigiLockerSession;
import com.navix.verification.dto.SignzyDtos.ExperianResponse;
import com.navix.verification.dto.SignzyDtos.LivenessResult;
import com.navix.verification.dto.SignzyDtos.PanResponse;
import com.navix.verification.exception.VerificationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
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
        stub(b.server(), "/api/v3/bankaccountverification/bankaccountverifications", """
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
    void pan206abMapsTopLevelFields() {
        Bound b = bind();
        stub(b.server(), "/api/v3/pan/compliance-206-individual-search", """
                {"number":"ABCPE1234Z","entityName":"  FXXL NXXE","panAllotmentDate":"20-07-2010",
                "panAadhaarLinkStatus":"linked","compliant":"true","isSpecified":"No","panStatus":"operative"}
                """);

        PanResponse r = new SignzyPanClient(b.restClient()).verify("ABCPE1234Z");

        assertThat(r.number()).isEqualTo("ABCPE1234Z");
        assertThat(r.entityName()).isEqualTo("FXXL NXXE");
        assertThat(r.panAadhaarLinkStatus()).isEqualTo("linked");
        assertThat(r.isSpecified()).isEqualTo("No");
        assertThat(r.panStatus()).isEqualTo("operative");
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

        ExperianResponse r = new SignzyExperianClient(b.restClient(), new ObjectMapper(), "")
                .pull("ABCPE1234Z", "Ravi Kumar", "9999999999", "1990-01-01");

        assertThat(r.txnId()).isEqualTo("1712213567495");
        assertThat(r.creditScore()).isEqualTo(742);
        assertThat(r.noRecord()).isFalse();
        assertThat(r.facts()).isNotNull();
        assertThat(r.facts().activeAccounts()).isEqualTo(3);
        assertThat(r.facts().totalBalanceRupees()).isEqualTo(120000L);
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
        b.server().verify();
    }

    @Test
    void livenessGetDataMapsResult() {
        Bound b = bind();
        stub(b.server(), "/api/v3/liveness-secure/getData", """
                {"result":{"token":"TKN-1","passiveLiveliness":{"liveness":true,"score":0.97},
                "faceMatch":{"verified":true,"matchPercentage":"98.00%"},"status":true}}
                """);

        LivenessResult r = new SignzyLivenessClient(b.restClient()).getData("TKN-1");

        assertThat(r.txnId()).isEqualTo("TKN-1");
        assertThat(r.live()).isTrue();
        assertThat(r.livenessScore()).isEqualTo(0.97);
        assertThat(r.faceVerified()).isTrue();
        assertThat(r.overallStatus()).isTrue();
        b.server().verify();
    }

    @Test
    void digilockerCreateUrlAndEAadhaar() {
        Bound b = bind();
        stub(b.server(), "/api/v3/digilocker/createUrl", """
                {"result":{"url":"https://api.digitallocker.gov.in/.../authorize?state=REQ-1","requestId":"REQ-1"}}
                """);
        DigiLockerSession s = new SignzyDigiLockerClient(b.restClient())
                .createUrl("https://navix.app/kyc/callback?app=1&sid=n", true);
        assertThat(s.requestId()).isEqualTo("REQ-1");
        assertThat(s.url()).contains("authorize");
        b.server().verify();

        Bound b2 = bind();
        stub(b2.server(), "/api/v3/digilocker/geteaadhaarwithxml", """
                {"result":{"name":"  NAME","uid":"xxxxxxxx0353","dob":"01/01/1990","gender":"FEMALE",
                "x509Data":{"validAadhaarDSC":"yes"},"address":"addr",
                "splitAddress":{"state":[["TAMIL NADU","TN"]],"pincode":"612001"},
                "photo":"https://persist.signzy.tech/p.jpeg","xmlFileLink":"https://persist.signzy.tech/a.xml"}}
                """);
        AadhaarResponse a = new SignzyDigiLockerClient(b2.restClient()).getEAadhaar("REQ-1");
        assertThat(a.fullName()).isEqualTo("NAME");
        assertThat(a.maskedUid()).isEqualTo("xxxxxxxx0353");
        assertThat(a.validDsc()).isTrue();
        assertThat(a.state()).isEqualTo("TAMIL NADU");
        assertThat(a.xmlUrl()).endsWith(".xml");
        b2.server().verify();
    }

    @Test
    void serverErrorBecomesVerificationException() {
        Bound b = bind();
        b.server().expect(requestTo(BASE + "/api/v3/pan/compliance-206-individual-search"))
                .andRespond(withServerError());

        SignzyPanClient client = new SignzyPanClient(b.restClient());
        assertThatThrownBy(() -> client.verify("BAD"))
                .isInstanceOf(VerificationException.class);
        b.server().verify();
    }
}
