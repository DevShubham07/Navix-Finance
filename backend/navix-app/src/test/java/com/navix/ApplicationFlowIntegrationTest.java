package com.navix;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test of the application state machine (dfd.md §8) over the FULL app:
 * real controllers → services → Flyway-migrated Postgres (Testcontainers). Drives a borrower's
 * loan from creation to ACTIVE through the staff workflow, asserting each transition, the
 * separation-of-duties guard, and the minted loan economics — exactly the "customer applies →
 * staff processes" path, verified automatically.
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

    @Test
    void customerAppliesAndStaffWalksItToActive() throws Exception {
        long appId = createApplication(7L);
        assertStatusEquals(appId, "DRAFT");

        act(appId, "submit-kyc", "b7", "BORROWER", null, "KYC_PENDING");
        act(appId, "kyc-decision", "ananya", "KYC_APPROVER", "{\"decision\":true}", "KYC_APPROVED");
        act(appId, "apply", "b7", "BORROWER",
                "{\"amountPaise\":1000000,\"purpose\":\"medical\",\"eligibleLimitPaise\":1250000,\"salaryCreditDay\":30}",
                "KYC_APPROVED");
        act(appId, "assign", "priya", "CREDIT_HEAD", "{\"executiveId\":55}", "CREDIT_EXEC_PENDING");
        act(appId, "exec-decision", "rahul", "CREDIT_EXECUTIVE", "{\"decision\":true}", "CREDIT_HEAD_PENDING");
        act(appId, "head-decision", "priya", "CREDIT_HEAD", "{\"decision\":true}", "DISBURSEMENT_PENDING");
        act(appId, "disbursement-decision", "vikram", "DISBURSEMENT_HEAD", "{\"decision\":true}", "ACCOUNTANT_PENDING");
        act(appId, "accountant-validate", "deepa", "ACCOUNTANT", "{\"decision\":true}", "ACTIVE");

        // The application now links a minted, ACTIVE loan with the correct paise economics.
        MvcResult appView = mvc.perform(get("/api/applications/{id}", appId)
                        .header("X-Demo-Actor-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loanId", notNullValue()))
                .andReturn();
        long loanId = data(appView).get("loanId").asLong();

        mvc.perform(get("/api/loan/{id}", loanId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.netDisbursedPaise").value(882000))          // ₹8,820 (tenure-independent)
                .andExpect(jsonPath("$.data.totalRepayablePaise", greaterThan(1_000_000))) // principal + salary-linked interest
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // The approval trail recorded the full chain.
        mvc.perform(get("/api/applications/{id}/events", appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.action=='ACTIVATE')]").exists());
    }

    @Test
    void separationOfDutiesBlocksSameActorAsExecutiveAndHead() throws Exception {
        long appId = createApplication(8L);
        act(appId, "submit-kyc", "b8", "BORROWER", null, "KYC_PENDING");
        act(appId, "kyc-decision", "ananya", "KYC_APPROVER", "{\"decision\":true}", "KYC_APPROVED");
        act(appId, "apply", "b8", "BORROWER", "{\"amountPaise\":1000000}", "KYC_APPROVED");
        act(appId, "assign", "boss", "CREDIT_HEAD", "{\"executiveId\":55}", "CREDIT_EXEC_PENDING");
        act(appId, "exec-decision", "sameperson", "CREDIT_EXECUTIVE", "{\"decision\":true}", "CREDIT_HEAD_PENDING");

        // The same human who recommended cannot give final approval (dfd.md D3).
        mvc.perform(post("/api/applications/{id}/head-decision", appId)
                        .header("X-Demo-Actor-Id", "sameperson")
                        .header("X-Demo-Actor-Role", "CREDIT_HEAD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("SOD_VIOLATION"));
    }

    @Test
    void illegalTransitionIsRejected() throws Exception {
        long appId = createApplication(9L);
        // Approving credit before the application has even been recommended is illegal.
        mvc.perform(post("/api/applications/{id}/exec-decision", appId)
                        .header("X-Demo-Actor-Id", "rahul")
                        .header("X-Demo-Actor-Role", "CREDIT_EXECUTIVE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_TRANSITION"));
    }

    // ---- helpers -------------------------------------------------------------------

    private long createApplication(long applicantId) throws Exception {
        MvcResult result = mvc.perform(post("/api/applications")
                        .header("X-Demo-Actor-Id", "borrower" + applicantId)
                        .header("X-Demo-Actor-Role", "BORROWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicantId\":" + applicantId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        return data(result).get("id").asLong();
    }

    /** Perform a transition and assert the resulting application status. */
    private void act(long appId, String action, String actorId, String role, String body,
                     String expectedStatus) throws Exception {
        var request = post("/api/applications/{id}/{action}", appId, action)
                .header("X-Demo-Actor-Id", actorId)
                .header("X-Demo-Actor-Role", role)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request = request.content(body);
        }
        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expectedStatus));
    }

    private void assertStatusEquals(long appId, String expected) throws Exception {
        mvc.perform(get("/api/applications/{id}", appId).header("X-Demo-Actor-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expected));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return om.readTree(result.getResponse().getContentAsString()).get("data");
    }
}
