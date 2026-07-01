package com.navix;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.security.JwtService;
import com.navix.loan.service.ApplicationVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test of the application state machine (dfd.md §8) over the FULL app:
 * real controllers → services → Flyway-migrated Postgres (Testcontainers), now over the real
 * JWT auth chain (P6). Each actor is a minted bearer token; the onboarding verification gate
 * (P3) is mocked-PASS here so this test stays focused on the maker-checker lifecycle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class ApplicationFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JwtService jwt;

    /** The onboarding completeness gate is exercised by unit tests; here it is PASS so the
     *  lifecycle test reaches KYC_PENDING without seeding every external verification. */
    @MockBean
    private ApplicationVerificationService verificationService;

    @BeforeEach
    void allowSubmitKyc() {
        when(verificationService.allRequiredPassed(anyLong())).thenReturn(true);
    }

    @Test
    void customerAppliesAndStaffWalksItToActive() throws Exception {
        long appId = createApplication(7L);
        assertStatusEquals(appId, "DRAFT");

        act(appId, "submit-kyc", "7", "BORROWER", null, "KYC_PENDING");
        act(appId, "kyc-decision", "11", "KYC_APPROVER", "{\"decision\":true}", "KYC_APPROVED");
        act(appId, "apply", "7", "BORROWER",
                "{\"amountPaise\":1000000,\"purpose\":\"medical\",\"eligibleLimitPaise\":1250000,\"salaryCreditDay\":30}",
                "KYC_APPROVED");
        act(appId, "assign", "12", "CREDIT_HEAD", "{\"executiveId\":2}", "CREDIT_EXEC_PENDING");
        act(appId, "exec-decision", "13", "CREDIT_EXECUTIVE", "{\"decision\":true}", "CREDIT_HEAD_PENDING");
        act(appId, "head-decision", "12", "CREDIT_HEAD", "{\"decision\":true}", "DISBURSEMENT_PENDING");
        act(appId, "disbursement-decision", "14", "DISBURSEMENT_HEAD", "{\"decision\":true}", "ACCOUNTANT_PENDING");
        act(appId, "accountant-validate", "15", "ACCOUNTANT", "{\"decision\":true}", "ACTIVE");

        MvcResult appView = mvc.perform(get("/api/applications/{id}", appId)
                        .header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loanId", notNullValue()))
                .andReturn();
        long loanId = data(appView).get("loanId").asLong();

        mvc.perform(get("/api/loan/{id}", loanId).header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.netDisbursedPaise").value(882000))
                .andExpect(jsonPath("$.data.totalRepayablePaise", greaterThan(1_000_000)))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mvc.perform(get("/api/applications/{id}/events", appId).header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.action=='ACTIVATE')]").exists());
    }

    @Test
    void adminWalksApplicationToActiveSolo() throws Exception {
        long appId = createApplication(10L);
        assertStatusEquals(appId, "DRAFT");

        act(appId, "submit-kyc", "10", "BORROWER", null, "KYC_PENDING");
        act(appId, "kyc-decision", "1", "ADMIN", "{\"decision\":true}", "KYC_APPROVED");
        act(appId, "apply", "10", "BORROWER",
                "{\"amountPaise\":1000000,\"purpose\":\"medical\",\"eligibleLimitPaise\":1250000,\"salaryCreditDay\":30}",
                "KYC_APPROVED");
        act(appId, "assign", "1", "ADMIN", "{\"executiveId\":10}", "CREDIT_EXEC_PENDING");
        act(appId, "exec-decision", "1", "ADMIN", "{\"decision\":true}", "CREDIT_HEAD_PENDING");
        act(appId, "head-decision", "1", "ADMIN", "{\"decision\":true}", "DISBURSEMENT_PENDING");
        act(appId, "disbursement-decision", "1", "ADMIN",
                "{\"decision\":true,\"txnRef\":\"ADMIN-TXN-10\"}", "ACTIVE");

        mvc.perform(get("/api/applications/{id}", appId).header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.loanId", notNullValue()));
    }

    @Test
    void separationOfDutiesBlocksSameActorAsExecutiveAndHead() throws Exception {
        long appId = createApplication(8L);
        act(appId, "submit-kyc", "8", "BORROWER", null, "KYC_PENDING");
        act(appId, "kyc-decision", "11", "KYC_APPROVER", "{\"decision\":true}", "KYC_APPROVED");
        act(appId, "apply", "8", "BORROWER", "{\"amountPaise\":1000000}", "KYC_APPROVED");
        act(appId, "assign", "12", "CREDIT_HEAD", "{\"executiveId\":2}", "CREDIT_EXEC_PENDING");
        act(appId, "exec-decision", "99", "CREDIT_EXECUTIVE", "{\"decision\":true}", "CREDIT_HEAD_PENDING");

        // The same human (id 99) who recommended cannot give final approval (dfd.md D3).
        mvc.perform(post("/api/applications/{id}/head-decision", appId)
                        .header("Authorization", bearer("99", "CREDIT_HEAD"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("SOD_VIOLATION"));
    }

    @Test
    void illegalTransitionIsRejected() throws Exception {
        long appId = createApplication(9L);
        mvc.perform(post("/api/applications/{id}/exec-decision", appId)
                        .header("Authorization", bearer("13", "CREDIT_EXECUTIVE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_TRANSITION"));
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/applications/{id}", 1).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ---- helpers -------------------------------------------------------------------

    private String bearer(String id, String role) {
        String audience = "BORROWER".equals(role) ? JwtService.AUDIENCE_BORROWER : JwtService.AUDIENCE_STAFF;
        return "Bearer " + jwt.issue(id, "Actor " + id, role, audience);
    }

    private long createApplication(long customerId) throws Exception {
        MvcResult result = mvc.perform(post("/api/applications")
                        .header("Authorization", bearer(String.valueOf(customerId), "BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        return data(result).get("id").asLong();
    }

    private void act(long appId, String action, String actorId, String role, String body,
                     String expectedStatus) throws Exception {
        var request = post("/api/applications/{id}/{action}", appId, action)
                .header("Authorization", bearer(actorId, role))
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request = request.content(body);
        }
        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expectedStatus));
    }

    private void assertStatusEquals(long appId, String expected) throws Exception {
        mvc.perform(get("/api/applications/{id}", appId).header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expected));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return om.readTree(result.getResponse().getContentAsString()).get("data");
    }
}
