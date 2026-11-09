package com.navix.app.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.Mandate;
import com.navix.loan.entity.MandateDebit;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.MandateDebitRepository;
import com.navix.loan.repository.MandateRepository;
import com.navix.loan.service.MandateDashboardService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The Mandates &amp; debits console's SQL against a real Flyway-migrated PostgreSQL. */
@SpringBootTest
@Testcontainers
@Tag("integration")
class MandateDashboardIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MandateDashboardService dashboard;
    @Autowired LoanApplicationRepository applications;
    @Autowired CustomerProfileRepository profiles;
    @Autowired MandateRepository mandates;
    @Autowired MandateDebitRepository debits;
    @Autowired StaffUserRepository staff;

    private Mandate active;
    private Mandate cancelled;
    private Long staffId;

    @BeforeEach
    void seed() {
        debits.deleteAll();
        mandates.deleteAll();
        profiles.deleteAll();
        applications.deleteAll();

        // Customer 9001: a live loan, a profile, one ACTIVE mandate and one staff-cancelled one.
        LoanApplication a1 = app(9001L, 501L);
        CustomerProfile p1 = new CustomerProfile();
        p1.setApplicationId(a1.getId());
        p1.setFullName("Kartik Jindal");
        p1.setMobile("7417682036");
        profiles.save(p1);
        active = mandate(9001L, a1.getId(), 1, Mandate.Status.ACTIVE, "PUNB7031810261019193", 7_500_000L);
        cancelled = mandate(9001L, a1.getId(), 2, Mandate.Status.AUTHORIZATION_FAILED, "PUNB0000000000000002", 500_000L);
        cancelled.setCancelledAt(Instant.now());
        cancelled.setCancelledBy("Asha (ADMIN, id 1)");
        mandates.save(cancelled);

        // Customer 9002: a draft with no profile yet and a pending mandate — still listed, name blank.
        LoanApplication a2 = app(9002L, null);
        mandate(9002L, a2.getId(), 1, Mandate.Status.PENDING_AUTHORIZATION, null, 1_000_000L);

        StaffUser s = new StaffUser();
        s.setEmail("asha-" + System.nanoTime() + "@navix.example");
        s.setName("Asha Collections");
        s.setRole(com.navix.iam.domain.StaffRole.COLLECTION_HEAD);
        s.setStatus(com.navix.iam.domain.StaffStatus.ACTIVE);
        staffId = staff.save(s).getId();

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        debit(active, 501L, 1, MandateDebit.Status.SUCCEEDED, MandateDebit.Trigger.MANUAL, staffId, 100L, today);
        debit(active, 501L, 2, MandateDebit.Status.FAILED, MandateDebit.Trigger.SCHEDULED, null, 129_000L, today);

        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    @Test
    void summaryCountsMandatesDebitsAndTheDailySeries() {
        var s = dashboard.summary();
        assertThat(s.totalMandates()).isEqualTo(3);
        assertThat(s.activeMandates()).isEqualTo(1);
        assertThat(s.activeLimitPaise()).isEqualTo(7_500_000L);
        assertThat(s.cancelled()).isEqualTo(1);
        assertThat(s.pendingAuthorisation()).isEqualTo(1);
        assertThat(s.debitsSucceeded()).isEqualTo(1);
        assertThat(s.debitedPaise()).isEqualTo(100L);
        assertThat(s.debitsFailed()).isEqualTo(1);
        assertThat(s.debitedByDay()).hasSize(30);
        assertThat(s.debitedByDay().get(29).debitedPaise()).isEqualTo(100L);
    }

    @Test
    void mandatesCarryTheCustomerTheirApplicationAndDebitTotals() {
        var page = dashboard.mandates(null, "ACTIVE", 0, 25);
        assertThat(page.total()).isEqualTo(1);
        var row = page.rows().get(0);
        assertThat(row.customerName()).isEqualTo("Kartik Jindal");
        assertThat(row.loanId()).isEqualTo(501L);
        assertThat(row.debitCount()).isEqualTo(2);
        assertThat(row.debitedPaise()).isEqualTo(100L);

        var all = dashboard.mandates(null, "ALL", 0, 25);
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.rows()).anyMatch(r -> r.customerName() == null); // the profile-less draft
        assertThat(dashboard.mandates(null, "CANCELLED", 0, 25).rows())
                .singleElement().extracting(MandateDashboardService.MandateRow::status).isEqualTo("CANCELLED");
    }

    @Test
    void searchMatchesNameMobileAndUmrn() {
        assertThat(dashboard.mandates("kartik", "ALL", 0, 25).total()).isEqualTo(2);
        assertThat(dashboard.mandates("74176", "ALL", 0, 25).total()).isEqualTo(2);
        assertThat(dashboard.mandates("punb7031", "ALL", 0, 25).total()).isEqualTo(1);
        assertThat(dashboard.mandates("nobody", "ALL", 0, 25).total()).isZero();
    }

    @Test
    void debitsShowWhoPresentedThemAndFilter() {
        var forMandate = dashboard.debitsForMandate(active.getId());
        assertThat(forMandate).hasSize(2);
        assertThat(forMandate).anyMatch(d -> "Asha Collections".equals(d.actorName()) && "MANUAL".equals(d.trigger()));
        assertThat(forMandate).allMatch(d -> "Kartik Jindal".equals(d.customerName()));

        assertThat(dashboard.debits(null, "ALL", "MANUAL", null, null, null, 0, 25).total()).isEqualTo(1);
        assertThat(dashboard.debits(null, "FAILED", null, null, null, null, 0, 25).total()).isEqualTo(1);
        assertThat(dashboard.debits(null, null, null, staffId, null, null, 0, 25).total()).isEqualTo(1);
        var page = dashboard.debits("kartik", null, null, null, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), 0, 25);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.succeededPaise()).isEqualTo(100L);
        assertThat(dashboard.actors()).extracting(MandateDashboardService.Actor::name).containsExactly("Asha Collections");
    }

    @Test
    void onlyCollectionHeadAndAdminMayRead() {
        ActorContext.set(new CurrentActor("7", "Agent", "DSA"));
        assertThatThrownBy(() -> dashboard.summary()).isInstanceOf(BusinessException.class);
        ActorContext.set(new CurrentActor("8", "Exec", "COLLECTION_EXECUTIVE"));
        assertThatThrownBy(() -> dashboard.mandates(null, null, 0, 25)).isInstanceOf(BusinessException.class);
        ActorContext.set(new CurrentActor("9", "Head", "COLLECTION_HEAD"));
        assertThat(dashboard.summary().totalMandates()).isEqualTo(3);
    }

    // ---- seed helpers ------------------------------------------------------------------------------

    private LoanApplication app(Long customerId, Long loanId) {
        LoanApplication a = new LoanApplication();
        a.setCustomerId(customerId);
        a.setLoanId(loanId);
        a.setStatus(loanId == null ? com.navix.loan.domain.ApplicationStatus.DRAFT
                : com.navix.loan.domain.ApplicationStatus.ACTIVE);
        a.setCreatedAt(Instant.now());
        return applications.save(a);
    }

    private Mandate mandate(Long customerId, Long appId, int attempt, Mandate.Status status, String umrn, long maxPaise) {
        Mandate m = new Mandate();
        m.setCustomerId(customerId);
        m.setLoanApplicationId(appId);
        m.setAttemptNo(attempt);
        m.setDigioMandateId("ENA" + customerId + "-" + attempt);
        m.setCustomerRefNumber("C" + customerId + "A" + attempt);
        m.setStatus(status);
        m.setUmrn(umrn);
        m.setBankName("Punjab National Bank");
        m.setAccountLast4("7860");
        m.setMaximumAmountPaise(maxPaise);
        return mandates.save(m);
    }

    private void debit(Mandate m, Long loanId, int attempt, MandateDebit.Status status, MandateDebit.Trigger trigger,
                       Long actorId, long amount, LocalDate settle) {
        MandateDebit d = new MandateDebit();
        d.setMandateId(m.getId());
        d.setLoanId(loanId);
        d.setUniqueKey("DB" + m.getId() + "-" + attempt);
        d.setAttemptNo(attempt);
        d.setAmountPaise(amount);
        d.setStatus(status);
        d.setTriggeredBy(trigger);
        d.setActorId(actorId);
        d.setSettlementDate(settle);
        debits.save(d);
    }
}
