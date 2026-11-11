package com.navix.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.navix.common.verification.EsignPort.ContractBox;
import com.navix.common.verification.EsignPort.ContractRequest;
import com.navix.common.verification.EsignPort.ContractSignerSpec;
import com.navix.common.verification.EsignPort.ContractStatus;
import com.navix.verification.client.SignzyContractClient;
import com.navix.verification.config.EsignProperties;
import com.navix.verification.dto.SignzyDtos.ContractInitiateDetail;
import com.navix.verification.dto.SignzyDtos.ContractInitiateRequest;
import com.navix.verification.dto.SignzyDtos.ContractPullDetail;
import com.navix.verification.dto.SignzyDtos.ContractSignerLink;
import com.navix.verification.dto.SignzyDtos.ContractSignerPull;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Multi-signer contract mapping: adapter request building, and client parsing of N signers. */
class SignzyEsignMultiSignerTest {

    private static final EsignProperties PROPS = new EsignProperties("signzy", "https://api.test/hook",
            "secret", "0.90", null, "#000000", "#111111", "NAVIX Finance", "bottom-left");

    private static ContractInitiateRequest sentFor(EsignProperties props, ContractBox... boxes) {
        SignzyContractClient client = mock(SignzyContractClient.class);
        when(client.initiateMulti(any())).thenReturn(new ContractInitiateDetail("c-1", List.of(
                new ContractSignerLink("s-1", "https://sign/1"))));
        new SignzyEsignAdapter(client, props).initiateContract(new ContractRequest(
                "https://s3/doc.pdf", "Vendor MSA", null, "https://app/ok", "https://app/no",
                List.of(new ContractSignerSpec("Asha Rao", List.of(boxes)))));
        ArgumentCaptor<ContractInitiateRequest> sent = ArgumentCaptor.forClass(ContractInitiateRequest.class);
        org.mockito.Mockito.verify(client).initiateMulti(sent.capture());
        return sent.getValue();
    }

    @Test
    void initiateContractSendsOneCustomizePlacementPerBoxWithNumericPages() {
        ContractInitiateRequest r = sentFor(PROPS,
                new ContractBox(1, 50.4, 100, 160, 70, 842), new ContractBox(3, 10, 20, 130.6, 70, 842));
        assertThat(r.pdf()).isEqualTo("https://s3/doc.pdf");
        assertThat(r.nameMatchThreshold()).isEqualTo("0.90");
        assertThat(r.allowSignerYobMatch()).isNull();
        var sigs = r.signerdetail().get(0).signatures();
        assertThat(sigs).hasSize(2);
        assertThat(sigs.get(0).pageNo()).containsExactly(1);
        assertThat(sigs.get(0).signaturePosition()).containsExactly("CUSTOMIZE");
        assertThat(sigs.get(0).xCoordinate()).containsExactly(50);
        // bottom-left origin: 842 - 100 - 70
        assertThat(sigs.get(0).yCoordinate()).containsExactly(672);
        assertThat(sigs.get(0).height()).isEqualTo(70);
        assertThat(sigs.get(0).width()).isEqualTo(160);
        assertThat(sigs.get(1).pageNo()).containsExactly(3);
        assertThat(sigs.get(1).width()).isEqualTo(131);
        assertThat(sigs.get(1).yCoordinate()).containsExactly(752);
    }

    @Test
    void topLeftOriginPassesYThrough() {
        EsignProperties top = new EsignProperties("signzy", "https://api.test/hook", "secret", "0.90", null,
                "#000000", "#111111", "NAVIX Finance", "top-left");
        var sig = sentFor(top, new ContractBox(1, 50, 100, 160, 70, 842)).signerdetail().get(0).signatures().get(0);
        assertThat(sig.yCoordinate()).containsExactly(100);
    }

    @Test
    void borrowerPlacementJsonCarriesNoCoordinateKeys() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                new com.navix.verification.dto.SignzyDtos.ContractSignaturePlacement(
                        List.<Object>of("All"), List.of("BottomLeft")));
        assertThat(json).isEqualTo("{\"pageNo\":[\"All\"],\"signaturePosition\":[\"BottomLeft\"]}");
    }

    @Test
    void fetchContractNormalisesPerSignerStatusAndFlagsExpiry() {
        SignzyContractClient client = mock(SignzyContractClient.class);
        when(client.pullDetail("c-1")).thenReturn(new ContractPullDetail(true, false, 1, null, null, List.of(
                new ContractSignerPull("s-1", "SIGNED", null, "PASS"),
                new ContractSignerPull("s-2", "PENDING", "Name mismatch", "FAIL"),
                new ContractSignerPull("s-3", "DRAFT", null, null))));
        when(client.pullDetail("gone")).thenReturn(new ContractPullDetail(false, false, null, null, null, List.of()));
        SignzyEsignAdapter adapter = new SignzyEsignAdapter(client, PROPS);

        ContractStatus st = adapter.fetchContract("c-1");
        assertThat(st.expired()).isFalse();
        assertThat(st.completed()).isFalse();
        assertThat(st.signers()).extracting("status").containsExactly("SIGNED", "FAILED", "PENDING");
        assertThat(st.signers().get(1).error()).isEqualTo("Name mismatch");

        assertThat(adapter.fetchContract("gone").expired()).isTrue();
    }

    // ---- client: every signerdetail[] entry is read -------------------------------

    private static final String BASE = "https://signzy.test";

    @Test
    void clientReadsAllSignersOnInitiateAndPull() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/api/v3/contract/initiate")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"contractId":"c-9","signerdetail":[
                          {"signerId":"a","esignUrl":"https://e/a"},{"signerId":"b","esignUrl":"https://e/b"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v3/contract/pullData")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"contractId":"c-9","isCompleted":false,"signedSignerCount":1,
                         "auditCertificateUrl":"https://audit",
                         "signerdetail":[{"signerId":"a","status":"SIGNED","nameMatchResult":"PASS"},
                                         {"signerId":"b","status":"PENDING","errorMessage":"boom"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v3/contract/pullData")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Contract Not Found\"}"));
        SignzyContractClient client = new SignzyContractClient(builder.build());

        ContractInitiateDetail init = client.initiateMulti(new ContractInitiateRequest(
                "https://s3/x.pdf", "T", "E", "ok", "no", "hook", null, "EMUDHRA", null, List.of(), null, null, null));
        assertThat(init.contractId()).isEqualTo("c-9");
        assertThat(init.signers()).extracting("signerId").containsExactly("a", "b");
        assertThat(init.signers()).extracting("esignUrl").containsExactly("https://e/a", "https://e/b");

        ContractPullDetail pull = client.pullDetail("c-9");
        assertThat(pull.found()).isTrue();
        assertThat(pull.signers()).hasSize(2);
        assertThat(pull.signers().get(0).nameMatchResult()).isEqualTo("PASS");
        assertThat(pull.signers().get(1).errorMessage()).isEqualTo("boom");
        assertThat(pull.auditCertificateUrl()).isEqualTo("https://audit");

        assertThat(client.pullDetail("c-9").found()).isFalse();
    }
}
