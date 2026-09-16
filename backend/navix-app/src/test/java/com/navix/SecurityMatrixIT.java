package com.navix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.security.JwtService;
import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.ApplicationVerificationService;
import java.time.LocalDate;
import java.util.List;
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
    @Autowired
    private StaffUserRepository staffUserRepository;
    @Autowired
    private LoanRepository loanRepository;

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

        // An ACCOUNTANT is staff but not on the credit team → FORBIDDEN_ROLE before any state check.
        mvc.perform(post("/api/applications/{id}/kyc-decision", appId)
                        .header("Authorization", bearer("15", "ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    /**
     * The staff namespace is closed to borrower tokens at the URL, not merely at whatever
     * {@code requireRole} the target service happens to carry. Regression: the audience claim was
     * minted and never read, so a borrower bearer authenticated onto every {@code /api/staff/**}
     * route and was stopped only where a service checked — {@code InviteService.acceptInvite}
     * deliberately does not.
     *
     * <p>401 rather than 403: the chain's {@code HttpStatusEntryPoint(UNAUTHORIZED)} answers the
     * denial. Verified against the live ALB on 2026-07-26 — the borrower token is valid (it still
     * gets 200 on {@code /api/payment-settings}), so this is the namespace gate rejecting it, not
     * the token being unreadable.
     */
    @Test
    void borrowerBearer_onStaffNamespace_isRejected() throws Exception {
        mvc.perform(get("/api/staff/me").header("Authorization", bearer("21", "BORROWER")))
                .andExpect(status().isUnauthorized());
    }

    /** The same borrower token still reaches the any-authed routes — the gate is scoped, not blanket. */
    @Test
    void borrowerBearer_onSharedRoute_isAllowed() throws Exception {
        mvc.perform(get("/api/payment-settings").header("Authorization", bearer("21", "BORROWER")))
                .andExpect(status().isOk());
    }

    /** Positive control: the matcher must not have closed the namespace to staff too. */
    @Test
    void staffBearer_onStaffNamespace_isNotRejected() throws Exception {
        mvc.perform(get("/api/staff/me").header("Authorization", bearer("1", "ADMIN")))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isNotIn(401, 403));
    }

    /**
     * Single-session enforcement (V54): a staff token whose {@code sid} no longer matches
     * {@code staff_user.active_session_id} is treated as no token at all — this is what actually
     * kicks a superseded session out, on its very next request.
     */
    @Test
    void supersededStaffToken_isRejectedWith401() throws Exception {
        StaffUser staff = new StaffUser();
        staff.setEmail("supersession-it@navix.example");
        staff.setName("Session Test");
        staff.setRole(StaffRole.ADMIN);
        staff.setStatus(StaffStatus.ACTIVE);
        staff.setActiveSessionId("the-current-session");
        staff = staffUserRepository.save(staff);

        String staleToken = jwt.issue(String.valueOf(staff.getId()), staff.getName(), "ADMIN",
                JwtService.AUDIENCE_STAFF, "an-older-superseded-session");

        mvc.perform(get("/api/staff/me").header("Authorization", "Bearer " + staleToken))
                .andExpect(status().isUnauthorized());

        // The CURRENT session id still authenticates fine.
        String currentToken = jwt.issue(String.valueOf(staff.getId()), staff.getName(), "ADMIN",
                JwtService.AUDIENCE_STAFF, "the-current-session");
        mvc.perform(get("/api/staff/me").header("Authorization", "Bearer " + currentToken))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    // ---- DSA isolation (V55) --------------------------------------------------------
    // A DSA is firewalled from the platform: no customer data, no pipeline, no telecalling queue,
    // no application actions. Each of these is FORBIDDEN_ROLE (422) — the DSA bearer authenticates
    // fine (it is staff), but every one of these services rejects the role explicitly.

    @Test
    void dsaBearer_onStaffOnlyLeadsEndpoint_isForbiddenRole() throws Exception {
        mvc.perform(post("/api/leads")
                        .header("Authorization", bearer("50", "DSA"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"mobile\":\"9876543210\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    /**
     * A DSA is staff, so the namespace gate lets their bearer through — every customer read has to
     * turn them away itself. Covers the paged book and the two reads that replaced the retired
     * whole-book list, since a new customer endpoint that forgets `rejectDsa` is exactly how an
     * external commission agent would reach borrower data.
     */
    @Test
    void dsaBearer_onCustomersEndpoints_isForbiddenRole() throws Exception {
        for (String path : List.of("/api/customers/page", "/api/customers/summary",
                "/api/customers/book-stats", "/api/customers/by-ids?ids=1")) {
            mvc.perform(get(path).header("Authorization", bearer("50", "DSA")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        }
    }

    /** Positive control: the same reads are open to an ordinary staff bearer. */
    @Test
    void staffBearer_onCustomersEndpoints_isAllowed() throws Exception {
        for (String path : List.of("/api/customers/page", "/api/customers/book-stats",
                "/api/customers/by-ids?ids=1")) {
            mvc.perform(get(path).header("Authorization", bearer("7", "ACCOUNTANT")))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void dsaBearer_onApplicationsCreate_isForbiddenRole() throws Exception {
        // /api/applications POST requires BORROWER; a DSA staff bearer is rejected the same as any
        // other non-borrower actor.
        mvc.perform(post("/api/applications")
                        .header("Authorization", bearer("50", "DSA"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":50}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    /** Positive control: the DSA's own namespace is not itself closed to the DSA role. */
    @Test
    void dsaBearer_onDsaNamespace_isNotRejected() throws Exception {
        mvc.perform(get("/api/dsa/leads").header("Authorization", bearer("50", "DSA")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    /** The DSA namespace is closed to borrower tokens at the URL, same as /api/staff/**. */
    @Test
    void borrowerBearer_onDsaNamespace_isRejected() throws Exception {
        mvc.perform(get("/api/dsa/leads").header("Authorization", bearer("21", "BORROWER")))
                .andExpect(status().isUnauthorized());
    }

    // ---- helpers -------------------------------------------------------------------

    private String bearer(String id, String role) {
        String audience = "BORROWER".equals(role) ? JwtService.AUDIENCE_BORROWER : JwtService.AUDIENCE_STAFF;
        return "Bearer " + jwt.issue(id, "Actor " + id, role, audience);
    }

    // ------------------------------------------------------------------ loan reads
    //
    // GET /api/loan/{id}, /outstanding and /repayments had NO ownership check: any authenticated
    // token could read any borrower's loan, balance and repayment history by guessing the id. These
    // pin the guard that closes that, and that it stays out of the way of the staff who need it.

    @Test
    void borrowerBearer_onAnotherBorrowersLoan_isForbidden() throws Exception {
        long loanId = createLoan(31L);

        for (String path : loanReadPaths(loanId)) {
            mvc.perform(get(path).header("Authorization", bearer("32", "BORROWER")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }
    }

    @Test
    void borrowerBearer_onTheirOwnLoan_isAllowed() throws Exception {
        long loanId = createLoan(33L);

        for (String path : loanReadPaths(loanId)) {
            mvc.perform(get(path).header("Authorization", bearer("33", "BORROWER")))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void dsaBearer_onLoanReads_isForbiddenRole() throws Exception {
        long loanId = createLoan(34L);

        for (String path : loanReadPaths(loanId)) {
            mvc.perform(get(path).header("Authorization", bearer("50", "DSA")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        }
    }

    @Test
    void staffBearer_onAnyLoanRead_isAllowed() throws Exception {
        long loanId = createLoan(35L);

        for (String path : loanReadPaths(loanId)) {
            mvc.perform(get(path).header("Authorization", bearer("7", "ACCOUNTANT")))
                    .andExpect(status().isOk());
        }
    }

    /** Every by-id loan read that carries a borrower's money, including the repay screen's schedule. */
    private static List<String> loanReadPaths(long loanId) {
        return List.of(
                "/api/loan/" + loanId,
                "/api/loan/" + loanId + "/outstanding",
                "/api/loan/" + loanId + "/outstanding/schedule",
                "/api/loan/" + loanId + "/repayments");
    }

    private long createLoan(long customerId) {
        Loan loan = new Loan();
        loan.setCustomerId(customerId);
        loan.setPrincipal(1_000_000L);
        loan.setProcessingFee(100_000L);
        loan.setGst(18_000L);
        loan.setNetDisbursed(882_000L);
        loan.setDisbursedOn(LocalDate.now().minusDays(10));
        loan.setDueDate(LocalDate.now().plusDays(17));
        loan.setTotalRepayable(1_270_000L);
        loan.setOutstanding(1_270_000L);
        loan.setStatus(LoanStatus.ACTIVE);
        return loanRepository.save(loan).getId();
    }

    private long createApplication(long customerId) throws Exception {
        MvcResult result = mvc.perform(post("/api/applications")
                        .header("Authorization", bearer(String.valueOf(customerId), "BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + "}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = om.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get("id").asLong();
    }
}
