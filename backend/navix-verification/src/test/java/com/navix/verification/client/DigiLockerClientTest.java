package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.navix.verification.dto.DigiLockerDtos.AadhaarXmlResponse;
import com.navix.verification.dto.DigiLockerDtos.DocumentResponse;
import com.navix.verification.dto.DigiLockerDtos.InitializeResponse;
import com.navix.verification.dto.DigiLockerDtos.ListDocumentsResponse;
import com.navix.verification.dto.DigiLockerDtos.StatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Offline tests for the DigiLocker client (initialize / status / listDocuments / document /
 * aadhaarXml) using {@link MockRestServiceServer} against the REAL provider envelopes.
 */
class DigiLockerClientTest {

    private static final String BASE = "https://digilocker.test/__api/api/v1/";

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
    void initializeMapsSession() {
        Bound b = bind();
        stub(b.server(), "digilocker_initialize", """
                {"status":"success","transaction_id":"TXN-DL-INIT","data":{"client_id":"DL-CLIENT-1",
                "token":"tok-123","url":"https://digilocker.gov/consent/abc","expiry_seconds":600}}
                """);

        InitializeResponse r = new DigiLockerClient(b.restClient())
                .initialize("https://navix/callback", 10, true);

        assertThat(r.txnId()).isEqualTo("TXN-DL-INIT");
        assertThat(r.clientId()).isEqualTo("DL-CLIENT-1");
        assertThat(r.token()).isEqualTo("tok-123");
        assertThat(r.url()).isEqualTo("https://digilocker.gov/consent/abc");
        assertThat(r.expirySeconds()).isEqualTo(600);
        b.server().verify();
    }

    @Test
    void statusMapsCompletion() {
        Bound b = bind();
        stub(b.server(), "digilocker_status", """
                {"status":"success","transaction_id":"TXN-DL-STATUS","data":{"status":"COMPLETED",
                "completed":true,"failed":false,"aadhaar_linked":true,"error_description":null}}
                """);

        StatusResponse r = new DigiLockerClient(b.restClient()).status("DL-CLIENT-1");

        assertThat(r.txnId()).isEqualTo("TXN-DL-STATUS");
        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.completed()).isTrue();
        assertThat(r.failed()).isFalse();
        assertThat(r.aadhaarLinked()).isTrue();
        assertThat(r.errorDescription()).isNull();
        b.server().verify();
    }

    @Test
    void listDocumentsMapsArray() {
        Bound b = bind();
        stub(b.server(), "digilocker_list_documents", """
                {"status":"success","transaction_id":"TXN-DL-LIST","data":[
                {"file_id":"F1","name":"Aadhaar","doc_type":"ADHAR","file_type":"xml","downloaded":true,
                "issuer":"UIDAI","description":"Aadhaar card"},
                {"file_id":"F2","name":"PAN","doc_type":"PANCR","file_type":"pdf","downloaded":false,
                "issuer":"ITD","description":"PAN card"},
                {"file_id":"F3","name":"DL","doc_type":"DRVLC","file_type":"pdf","downloaded":false,
                "issuer":"RTO","description":"Driving licence"},
                {"file_id":"F4","name":"Marksheet","doc_type":"MARKS","file_type":"pdf","downloaded":false,
                "issuer":"CBSE","description":"Class X"}]}
                """);

        ListDocumentsResponse r = new DigiLockerClient(b.restClient()).listDocuments("DL-CLIENT-1");

        assertThat(r.txnId()).isEqualTo("TXN-DL-LIST");
        assertThat(r.documents()).hasSize(4);
        assertThat(r.documents().get(0).fileId()).isEqualTo("F1");
        assertThat(r.documents().get(0).docType()).isEqualTo("ADHAR");
        assertThat(r.documents().get(0).downloaded()).isTrue();
        b.server().verify();
    }

    @Test
    void documentMapsPresignedUrl() {
        Bound b = bind();
        stub(b.server(), "digilocker_document", """
                {"status":"success","transaction_id":"TXN-DL-DOC","data":{
                "download_url":"https://s3.example/aadhaar.xml?sig=xyz","mime_type":"application/xml"}}
                """);

        DocumentResponse r = new DigiLockerClient(b.restClient()).document("DL-CLIENT-1", "F1");

        assertThat(r.txnId()).isEqualTo("TXN-DL-DOC");
        assertThat(r.downloadUrl()).isEqualTo("https://s3.example/aadhaar.xml?sig=xyz");
        assertThat(r.mimeType()).isEqualTo("application/xml");
        b.server().verify();
    }

    @Test
    void aadhaarXmlMapsDemographics() {
        Bound b = bind();
        stub(b.server(), "digilocker_aadhar_xml", """
                {"status":"success","transaction_id":"TXN-DL-XML","data":{"aadhaar_xml_data":{
                "full_name":"Rahul Sharma","dob":"1992-08-15","gender":"M","masked_aadhaar":"XXXXXXXX4321",
                "full_address":"12 MG Road, New Delhi","father_name":"Mohan Sharma","profile_image":"BASE64IMG",
                "zip":"110001","address":{"state":"Delhi"}},"xml_url":"https://s3.example/aadhaar.xml"}}
                """);

        AadhaarXmlResponse r = new DigiLockerClient(b.restClient()).aadhaarXml("DL-CLIENT-1");

        assertThat(r.txnId()).isEqualTo("TXN-DL-XML");
        assertThat(r.fullName()).isEqualTo("Rahul Sharma");
        assertThat(r.maskedAadhaar()).isEqualTo("XXXXXXXX4321");
        assertThat(r.state()).isEqualTo("Delhi");
        assertThat(r.pincode()).isEqualTo("110001");
        assertThat(r.fatherName()).isEqualTo("Mohan Sharma");
        assertThat(r.profileImageBase64()).isEqualTo("BASE64IMG");
        assertThat(r.xmlUrl()).isEqualTo("https://s3.example/aadhaar.xml");
        b.server().verify();
    }
}
