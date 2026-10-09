package com.navix.app.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.app.dashboard.BusinessQueries.MonthCollection;
import com.navix.app.dashboard.DashboardDtos.AumRow;
import com.navix.app.dashboard.DashboardDtos.Records;
import com.navix.app.dashboard.DashboardDtos.Snapshot;
import com.navix.app.dashboard.MetricQueries.Agg;
import com.navix.app.dashboard.MetricSql.Metric;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The dashboard's metric fragments against a real Flyway-migrated Postgres. The invariant that
 * matters: for every snapshot metric, {@code records(metric).total} equals the card's count (and the
 * Fresh/Re-loan split), because both are built from the same fragment. Also pins the fresh/re-loan
 * rule, AUM buckets, collection % and pre-closure %.
 *
 * <p>Runs on a Testcontainers Postgres like the sibling ITs, or on an existing empty database named by
 * {@code NAVIX_IT_JDBC_URL}.
 */
@SpringBootTest
@Tag("integration")
class DashboardQueriesIntegrationTest {

    private static final String EXTERNAL_URL = System.getenv("NAVIX_IT_JDBC_URL");

    static PostgreSQLContainer<?> postgres = EXTERNAL_URL == null
            ? new PostgreSQLContainer<>("postgres:16-alpine") : null;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (postgres != null) {
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("NAVIX_IT_DB_USER", "navix"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("NAVIX_IT_DB_PASSWORD", "navix"));
        }
    }

    @Autowired MetricQueries metrics;
    @Autowired BusinessQueries business;
    @Autowired DashboardService service;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);
    private static final DashboardScope ADMIN = new DashboardScope("ADMIN", List.of(), true, "ADMIN", 1L);

    @BeforeEach
    void seed() {
        for (String t : List.of("settlement", "interaction_log", "collection_case", "payment", "loan",
                "application_verification", "customer_profile", "application_event", "loan_application")) {
            jdbc.update("delete from " + t);
        }

        // C1 -- fresh, disbursed 5 Jun, due 30 Jun, still running.
        long a1 = app(1, "ACTIVE", "2026-06-05", null, null);
        loan(a1, 1, "ACTIVE", 1_000_000, 100_000, "2026-06-05", "2026-06-30", 1_270_000, null);

        // C2 -- re-loan: an earlier loan closed 10 Apr, a new one disbursed 10 Jun and prepaid 20 Jun (due 10 Jul).
        long a0 = app(2, "CLOSED", "2026-03-01", null, null);
        loan(a0, 2, "CLOSED", 500_000, 50_000, "2026-03-01", "2026-03-31", 600_000, "2026-04-10");
        long a2 = app(2, "CLOSED", "2026-06-10", null, null);
        long l2 = loan(a2, 2, "CLOSED", 2_000_000, 200_000, "2026-06-10", "2026-07-10", 2_100_000, "2026-06-20");
        payment(l2, 2_100_000, "VERIFIED", "2026-06-20", false);

        // Pending / rejected / sanctioned / awaiting disbursal -- all fresh, all created in June.
        app(3, "KYC_PENDING", "2026-06-03", null, null);
        app(4, "REJECTED", "2026-06-04", null, null);
        app(5, "SANCTIONED", "2026-06-12", null, 500_000L);
        app(6, "DISBURSEMENT_PENDING", "2026-06-13", 700_000L, null);
        app(9, "DRAFT", "2026-06-14", null, null); // drafts are never counted

        // C7 -- disbursed in May, due 15 Jun, one verified part payment in June.
        long a7 = app(7, "ACTIVE", "2026-05-18", null, null);
        long l7 = loan(a7, 7, "ACTIVE", 500_000, 50_000, "2026-05-20", "2026-06-15", 600_000, null);
        payment(l7, 100_000, "VERIFIED", "2026-06-10", true);

        // C8 -- due 1 Jun, closed 25 Jun through an approved settlement.
        long a8 = app(8, "CLOSED", "2026-05-01", null, null);
        long l8 = loan(a8, 8, "CLOSED", 1_000_000, 100_000, "2026-05-01", "2026-06-01", 1_000_000, "2026-06-25");
        payment(l8, 900_000, "VERIFIED", "2026-06-25", false);
        UUID caseId = UUID.randomUUID();
        jdbc.update("insert into collection_case (id, loan_id, created_at) values (?, ?, now())", caseId, l8);
        jdbc.update("insert into settlement (id, collection_case_id, settlement_amount, status, proposed_by, created_at)"
                + " values (?, ?, 900000, 'APPROVED', 1, now())", UUID.randomUUID(), caseId);
    }

    private long app(long customer, String status, String createdOn, Long requested, Long sanctioned) {
        var at = LocalDate.parse(createdOn).atTime(12, 0).atZone(ZoneId.of("Asia/Kolkata")).toOffsetDateTime();
        Long id = jdbc.queryForObject("insert into loan_application (customer_id, status, created_at,"
                        + " amount_requested, sanctioned_amount_paise) values (?, ?, ?, ?, ?) returning id",
                Long.class, customer, status, at, requested, sanctioned);
        jdbc.update("insert into customer_profile (application_id, full_name, mobile, created_at, created_by)"
                + " values (?, ?, '9876543210', now(), 'test')", id, "Customer " + customer);
        return id;
    }

    private long loan(long appId, long customer, String status, long principal, long fee, String disbursedOn,
                      String dueDate, long total, String closedOn) {
        Long id = jdbc.queryForObject("insert into loan (customer_id, status, principal, processing_fee, net_disbursed,"
                        + " daily_interest_rate, disbursed_on, due_date, total_repayable, outstanding, closed_on, created_at)"
                        + " values (?, ?, ?, ?, ?, 0.0100, ?, ?, ?, ?, ?, now()) returning id",
                Long.class, customer, status, principal, fee, principal - fee - fee * 18 / 100,
                Date.valueOf(disbursedOn), Date.valueOf(dueDate), total, "CLOSED".equals(status) ? 0 : total,
                closedOn == null ? null : Date.valueOf(closedOn));
        jdbc.update("update loan_application set loan_id = ? where id = ?", id, appId);
        return id;
    }

    private void payment(long loanId, long amount, String status, String paidOn, boolean partial) {
        jdbc.update("insert into payment (loan_id, amount, method, status, paid_on, partial, created_at)"
                + " values (?, ?, 'UPI', ?, ?, ?, now())", loanId, amount, status, Date.valueOf(paidOn), partial);
    }

    private Agg agg(Metric m) {
        return metrics.aggregate(m, ADMIN, FROM, TO, null);
    }

    @Test
    void snapshotCountsAndTheFreshReloanSplit() {
        Agg applications = agg(Metric.APPLICATIONS);
        assertThat(applications.count()).isEqualTo(6); // A1, A2, A3, A4, A5, A6 -- not the draft, not May's
        assertThat(applications.reloan()).isEqualTo(1); // only C2's second application
        assertThat(applications.fresh()).isEqualTo(5);

        Agg disbursed = agg(Metric.DISBURSED);
        assertThat(disbursed.count()).isEqualTo(2);
        assertThat(disbursed.amount()).isEqualTo(3_000_000);
        assertThat(disbursed.reloanAmount()).isEqualTo(2_000_000);
        assertThat(disbursed.freshAmount()).isEqualTo(1_000_000);

        assertThat(agg(Metric.PENDING).count()).isEqualTo(3);
        assertThat(agg(Metric.REJECTED).count()).isEqualTo(1);
        assertThat(agg(Metric.PENDING_SANCTIONED).amount()).isEqualTo(500_000);
        assertThat(agg(Metric.PENDING_DISBURSAL).amount()).isEqualTo(700_000);
        assertThat(agg(Metric.CLOSED).count()).isEqualTo(2);  // C2 prepaid + C8 settled
        assertThat(agg(Metric.SETTLED).count()).isEqualTo(1); // C8 only
        assertThat(agg(Metric.PART_PAID).count()).isEqualTo(1);
    }

    @Test
    void recordsTotalEqualsTheCardCountForEverySnapshotMetric() {
        for (Metric m : List.of(Metric.APPLICATIONS, Metric.DISBURSED, Metric.PENDING, Metric.REJECTED,
                Metric.PENDING_SANCTIONED, Metric.PENDING_DISBURSAL, Metric.CLOSED, Metric.SETTLED, Metric.PART_PAID)) {
            Agg a = agg(m);
            Records all = service.records(ADMIN, m.name(), null, "ALL", null, 0, 50, FROM, TO);
            assertThat(all.total()).as("%s total", m).isEqualTo(a.count());
            assertThat(all.rows()).as("%s rows", m).hasSize((int) a.count());
            assertThat(all.freshCount()).as("%s fresh", m).isEqualTo(a.fresh());
            assertThat(all.reloanCount()).as("%s reloan", m).isEqualTo(a.reloan());
            assertThat(service.records(ADMIN, m.name(), null, "FRESH", null, 0, 50, FROM, TO).total())
                    .as("%s fresh segment", m).isEqualTo(a.fresh());
            assertThat(service.records(ADMIN, m.name(), null, "RELOAN", null, 0, 50, FROM, TO).total())
                    .as("%s reloan segment", m).isEqualTo(a.reloan());
        }
        Records disbursed = service.records(ADMIN, "DISBURSED", null, "ALL", null, 0, 50, FROM, TO);
        assertThat(disbursed.sumPaise()).isEqualTo(3_000_000L);
        assertThat(disbursed.rows()).allSatisfy(r -> {
            assertThat(r.customerName()).startsWith("Customer");
            assertThat(r.mobileLast4()).isEqualTo("3210");
        });
        assertThat(service.records(ADMIN, "APPLICATIONS", null, "ALL", "Customer 3", 0, 50, FROM, TO).total())
                .isEqualTo(1);
    }

    @Test
    void snapshotEndpointAgreesWithTheFragments() {
        Snapshot s = service.snapshot(ADMIN, FROM, TO);
        assertThat(s.kpis().applications().count()).isEqualTo(6);
        assertThat(s.kpis().disbursed().count()).isEqualTo(2);
        assertThat(s.closed().closedCount()).isEqualTo(2);
        assertThat(s.closed().settledCount()).isEqualTo(1);
        assertThat(s.closed().partPaidCount()).isEqualTo(1);
        // verified in June: 2,100,000 (C2, closed) + 900,000 (C8, settled) + 100,000 (C7, part)
        assertThat(s.closed().collectedClosedPaise()).isEqualTo(2_100_000);
        assertThat(s.closed().collectedSettledPaise()).isEqualTo(900_000);
        assertThat(s.closed().collectedPartPaise()).isEqualTo(100_000);
        assertThat(s.closed().totalCollectedPaise()).isEqualTo(3_100_000);
        assertThat(s.financial().averageLoanPaise()).isEqualTo(1_500_000);
        assertThat(s.rates().disbursementRate()).isEqualTo(2.0 / 6);
        // PF 10% for both disbursed loans, one fresh and one repeat
        assertThat(s.pfTable()).hasSize(1);
        assertThat(s.pfTable().get(0).ratePct()).isEqualTo(10.0);
        assertThat(s.pfTable().get(0).newCount()).isEqualTo(1);
        assertThat(s.pfTable().get(0).repeatCount()).isEqualTo(1);
    }

    @Test
    void aumBucketsFollowDaysPastDueAsOfTheDate() {
        LocalDate asOf = LocalDate.of(2026, 6, 12);
        Map<String, Agg> buckets = metrics.aggregateByGrp(Metric.AUM_BUCKET, ADMIN, asOf, asOf);
        // C1, C7 and C2 are not yet due; C8 (due 1 Jun, closed only on 25 Jun) is 11 days past due.
        assertThat(buckets.get("RUNNING").count()).isEqualTo(3);
        assertThat(buckets.get("D1_30").count()).isEqualTo(1);
        assertThat(buckets).doesNotContainKeys("D31_60", "D61_90", "D90_PLUS");
        AumRow total = service.aum(ADMIN, asOf).total();
        assertThat(total.cases()).isEqualTo(4);
        assertThat(service.records(ADMIN, "AUM_BUCKET", "D1_30", "ALL", null, 0, 50, FROM, asOf).total()).isEqualTo(1);
    }

    @Test
    void collectionAndPreclosurePercentages() {
        Map<String, MonthCollection> m = business.monthlyCollection(ADMIN, FROM, LocalDate.of(2026, 7, 31));
        MonthCollection june = m.get("2026-06"); // due in June: C1 (1.27M), C7 (0.6M), C8 (1.0M)
        assertThat(june.due()).isEqualTo(3);
        assertThat(june.repayable()).isEqualTo(2_870_000);
        assertThat(june.collected()).isEqualTo(1_000_000); // 100k part + 900k settlement
        assertThat(june.preclosed()).isZero();             // C8 closed after its due date
        MonthCollection july = m.get("2026-07");           // C2 prepaid on 20 Jun, due 10 Jul
        assertThat(july.due()).isEqualTo(1);
        assertThat(july.collected()).isEqualTo(july.repayable());
        assertThat(july.preclosed()).isEqualTo(1);

        assertThat(metrics.aggregate(Metric.COLLECTION_MONTH, ADMIN, FROM, TO, "2026-07").count()).isEqualTo(1);
        assertThat(service.records(ADMIN, "COLLECTION_MONTH", "2026-07", "ALL", null, 0, 50, FROM, TO).total())
                .isEqualTo(1);
        assertThat(service.records(ADMIN, "PRECLOSED_ON", "2026-07-10", "ALL", null, 0, 50, FROM, TO).total())
                .isEqualTo(1);
    }

    @Test
    void reloanRetentionCountsCustomersWhoCameBack() {
        // C2's April closure was followed by a June application; C8 closed on 25 Jun with no later one.
        Map<String, Agg> ret = metrics.aggregateByGrp(Metric.RELOAN_RETENTION, ADMIN, FROM, LocalDate.of(2026, 7, 31));
        assertThat(ret.get("DUE").count()).isEqualTo(4);   // C1, C7, C8 (June) + C2 (July)
        assertThat(ret.get("CLOSED").count()).isEqualTo(2); // C8, C2
        assertThat(ret).doesNotContainKey("RELOAN"); // nobody came back, so no group at all
        assertThat(ret.get("NO_REPEAT").count()).isEqualTo(2);
    }

    /** Parse-time guard: every endpoint and every metric's records query runs on the migrated schema. */
    @Test
    void everyEndpointAndMetricRunsOnTheRealSchema() {
        long staffId = 7L;
        jdbc.update("insert into collection_case (id, loan_id, assigned_officer_id, created_at)"
                + " select ?, id, ?, now() from loan limit 1", UUID.randomUUID(), staffId);
        service.snapshot(ADMIN, FROM, TO);
        service.monthly(ADMIN, FROM, TO, 4);
        service.daily(ADMIN, FROM, TO);
        service.preclosureWeek(ADMIN, LocalDate.of(2026, 6, 9));
        service.aum(ADMIN, TO);
        service.collectionAnalysis(ADMIN, LocalDate.of(2026, 6, 30), "EXEC");
        service.collectionAnalysis(ADMIN, LocalDate.of(2026, 6, 30), "STATE");
        service.team(ADMIN, FROM, TO);
        for (String mode : List.of("DUE", "DISBURSED", "BIRTHDAY")) service.calendar(ADMIN, "2026-06", mode);
        service.geo(ADMIN, FROM, TO);
        // AVG(salary) is numeric -- it must come back as paise (a bare getObject(Long) threw on it in production).
        jdbc.update("update customer_profile set employer = 'Acme Ltd', monthly_salary_paise = 7000001, employment_status = 'SALARIED'");
        assertThat(service.companies(ADMIN, FROM, TO).users()).isNotEmpty()
                .allSatisfy(u -> assertThat(u.averageSalaryPaise()).isEqualTo(7000001L));
        service.allocation(ADMIN, FROM, TO);
        for (String board : List.of("CREDIT_HEAD", "CREDIT_EXECUTIVE", "COLLECTION_HEAD", "COLLECTION_EXECUTIVE",
                "TELECALLER")) {
            service.leaderboard(ADMIN, board, FROM, TO);
        }
        for (String view : List.of("CREDIT_HEAD", "CREDIT_EXECUTIVE", "COLLECTION_HEAD", "COLLECTION_EXECUTIVE",
                "TELECALLER", "DISBURSEMENT_HEAD", "ACCOUNTANT")) {
            DashboardScope sc = new DashboardScope(view, List.of(staffId), false, "ADMIN", 1L);
            service.roleView(sc, FROM, TO);
            service.monthly(sc.viewIn("COLLECTION_HEAD", "COLLECTION_EXECUTIVE") ? sc : ADMIN, FROM, TO, 2);
        }
        for (Metric m : Metric.values()) {
            for (String view : m.views()) {
                DashboardScope sc = "ADMIN".equals(view) ? ADMIN
                        : new DashboardScope(view, List.of(staffId), false, "ADMIN", 1L);
                for (String segment : List.of("ALL", "FRESH")) {
                    service.records(sc, m.name(), null, segment, "x", 0, 10, FROM, TO);
                }
            }
        }
        service.records(ADMIN, "STAFF_FILES", "7", "ALL", null, 0, 10, FROM, TO);
        service.records(ADMIN, "COLLECTION_GROUP", "EXEC:NONE:2026-06-30", "ALL", null, 0, 10, FROM, TO);
        service.records(ADMIN, "COMPANY", "Acme Ltd", "ALL", null, 0, 10, FROM, TO);
        service.records(ADMIN, "PF_RATE", "10", "ALL", null, 0, 10, FROM, TO);
        service.records(ADMIN, "CALENDAR_BIRTHDAY", "2026-06-15", "ALL", null, 0, 10, FROM, TO);
        assertThat(business.targets(java.time.YearMonth.of(2026, 1), java.time.YearMonth.of(2026, 12))).isEmpty();
    }

    @Test
    void staffFilesRecordsWorkWithoutAKeyAndByClickedDay() {
        Long app = jdbc.queryForObject("select id from loan_application where status = 'KYC_PENDING'", Long.class);
        var at = LocalDate.of(2026, 6, 8).atTime(11, 0).atZone(ZoneId.of("Asia/Kolkata")).toOffsetDateTime();
        jdbc.update("insert into application_event (application_id, from_status, to_status, actor_id, actor_role,"
                + " action, notes, at) values (?, 'KYC_PENDING', 'CREDIT_EXEC_PENDING', '1', 'CREDIT_HEAD',"
                + " 'ASSIGN', 'executiveId=7', ?)", app, at);
        DashboardScope credit = new DashboardScope("CREDIT_HEAD", List.of(7L, 8L), false, "CREDIT_HEAD", 1L);
        LocalDate day = LocalDate.of(2026, 6, 8);
        Records noKey = service.records(credit, "STAFF_FILES", null, "ALL", null, 0, 50, day, day);
        Records withKey = service.records(credit, "STAFF_FILES", "7", "ALL", null, 0, 50, day, day);
        assertThat(noKey.total()).isEqualTo(1);
        assertThat(withKey.total()).isEqualTo(1);
        assertThat(service.records(credit, "STAFF_FILES", null, "ALL", null, 0, 50, day.plusDays(1), day.plusDays(1))
                .total()).isZero();
        assertThat(service.records(credit, "STAFF_FILES", "8", "ALL", null, 0, 50, day, day).total()).isZero();
        assertThat(metrics.aggregate(Metric.STAFF_FILES, credit, day, day, null).count()).isEqualTo(1);
        org.junit.jupiter.api.Assertions.assertThrows(com.navix.common.exception.BusinessException.class,
                () -> service.records(credit, "STAFF_FILES", "99", "ALL", null, 0, 50, day, day));
    }
}
