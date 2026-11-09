package com.navix.app.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.navix.app.report.ReportDtos.ReportDef;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.RepaymentService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** The SQL is pinned against the real schema by hand-run checks; this covers the gate and the plumbing. */
class ReportServiceTest {

    private final ReportService service = new ReportService(mock(JdbcTemplate.class), mock(LoanRepository.class),
            mock(RepaymentService.class));

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    @Test
    void onlyAdminMayListOrRunReports() {
        for (String role : List.of("CREDIT_HEAD", "ACCOUNTANT", "COLLECTION_HEAD", "DSA", "BORROWER")) {
            ActorContext.set(new CurrentActor("5", "x", role));
            assertThatThrownBy(service::catalog).isInstanceOf(BusinessException.class).hasMessageContaining("ADMIN");
            assertThatThrownBy(() -> service.run("customers", null, null)).isInstanceOf(BusinessException.class);
        }
        // The working role is not enough: an ADMIN acting as a Credit Head still reads reports, and a
        // Credit Head claiming the ADMIN working role does not.
        ActorContext.set(new CurrentActor("5", "x", "CREDIT_HEAD", "ADMIN"));
        assertThatThrownBy(service::catalog).isInstanceOf(BusinessException.class);
        ActorContext.set(new CurrentActor("1", "x", "ADMIN", "CREDIT_HEAD"));
        assertThat(service.catalog()).isNotEmpty();
    }

    @Test
    void catalogKeysAreUniqueAndUnknownKeysAreRefused() {
        ActorContext.set(new CurrentActor("1", "x", "ADMIN"));
        List<ReportDef> defs = service.catalog();
        assertThat(new HashSet<>(defs.stream().map(ReportDef::key).toList())).hasSize(defs.size());
        assertThatThrownBy(() -> service.run("nope", null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("nope");
    }

    @Test
    void everyReportHasAOneLineExplanationAndASidebarCategory() {
        // Mirrors frontend/src/lib/reports/categories.ts — the sidebar filters on these exact labels.
        List<String> sidebar = List.of("Business overview", "Customers", "Disbursement", "Repayments & collections",
                "Risk & portfolio quality", "Applications & credit", "Staff & productivity", "Finance & tax",
                "Compliance & audit", "Growth & partners");
        for (ReportDef d : ReportService.CATALOG) {
            assertThat(sidebar).as(d.key()).contains(d.category());
            assertThat(d.description()).as(d.key()).isNotBlank().doesNotContain("\n");
            assertThat(d.description().length()).as(d.key()).isLessThanOrEqualTo(140);
        }
        assertThat(ReportService.CATALOG.stream().map(ReportDef::category).distinct()).hasSameSizeAs(sidebar);
    }

    @Test
    void periodIsAllTimeWhenBothEndsAreMissingAndOpenEndsAreFilled() {
        assertThat(ReportService.Period.of(null, null).all()).isTrue();
        assertThat(ReportService.Period.ALL.on("x")).isEqualTo("true");

        ReportService.Period p = ReportService.Period.of(LocalDate.of(2026, 10, 1), null);
        assertThat(p.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(p.to()).isNotNull();
        assertThat(p.on("d")).isEqualTo("(d between :from and :to)");
        assertThat(p.params().getValue("from")).isEqualTo(LocalDate.of(2026, 10, 1));

        assertThatThrownBy(() -> ReportService.Period.of(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cellsAreNormalisedToJsonFriendlyValues() {
        assertThat(ReportService.normalize(java.sql.Date.valueOf("2026-10-01"))).isEqualTo("2026-10-01");
        Instant at = Instant.parse("2026-10-01T05:30:00Z");
        assertThat(ReportService.normalize(Timestamp.from(at))).isEqualTo(at.toString());
        assertThat(ReportService.normalize(7)).isEqualTo(7L);
        assertThat(ReportService.normalize(new BigDecimal("1200000"))).isEqualTo(1_200_000L);
        assertThat(ReportService.normalize(new BigDecimal("66.7"))).isEqualTo(66.7);
        assertThat(ReportService.normalize(null)).isNull();
    }
}
