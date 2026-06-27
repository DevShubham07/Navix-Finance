package com.navix;

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
 * Security matrix over the real JWT auth chain + maker-checker RBAC (P6): an unauthenticated
 * request is rejected at the gate (401), and an authenticated-but-wrong-role actor is rejected at
 * {@code requireRole} (422 {@code FORBIDDEN_ROLE}) — whether the bearer is a borrower or a
 * mis-roled staffer. The proposer≠approver SoD case is covered by {@link ApplicationFlowIntegrationTest}.
 *
 * <p>Docker-only (Testcontainers Postgres); excluded from {@code ./mvnw test} via {@code @Tag("integration")}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class SecurityMatrixIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JwtService jwt;

    @MockBean
    private ApplicationVerificationService verificationService;

    @BeforeEach
    void allowSubmitKyc() {
        when(verificationService.allRequiredPassed(anyLong())).thenReturn(true);
    }

    @Test
    void unauthenticatedRead_isRejectedWith401() throws Exception {
        mvc.perform(get("/api/applications/{id}", 1).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void borrowerBearer_onStaffOnlyAction_isForbiddenRole() throws Exception {
        long appId = createApplication(21L);

        // A borrower token authenticates onto /api/applications/* but cannot drive a KYC decision.
        mvc.perform(post("/api/applications/{id}/kyc-decision", appId)
                        .header("Authorization", bearer("21", "BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void wrongRoleStaffBearer_onKycDecision_isForbiddenRole() throws Exception {
        long appId = createApplication(22L);

        // An ACCOUNTANT is staff but not a KYC_APPROVER → FORBIDDEN_ROLE before any state check.
        mvc.perform(post("/api/applications/{id}/kyc-decision", appId)
                        .header("Authorization", bearer("15", "ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    // ---- helpers -------------------------------------------------------------------

    private String bearer(String id, String role) {
        String audience = "BORROWER".equals(role) ? JwtService.AUDIENCE_BORROWER : JwtService.AUDIENCE_STAFF;
        return "Bearer " + jwt.issue(id, "Actor " + id, role, audience);
    }

    private long createApplication(long applicantId) throws Exception {
        MvcResult result = mvc.perform(post("/api/applications")
                        .header("Authorization", bearer(String.valueOf(applicantId), "BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicantId\":" + applicantId + "}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = om.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get("id").asLong();
    }
}
