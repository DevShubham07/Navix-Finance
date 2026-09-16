package com.navix.app.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.service.CustomerBookQuery;
import com.navix.loan.service.CustomerBookQuery.BookFilter;
import com.navix.loan.service.CustomerBookQuery.SegmentCount;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * {@link CustomerBookQuery} against a real Flyway-migrated Postgres: the SQL {@code segment} CASE
 * must agree with the frontend's {@code segmentOf()} for EVERY (loan status, application status)
 * pair, the page order must be stage-date-desc-nulls-last, and every filter must mean what the
 * in-memory {@code CustomerService.list} meant.
 *
 * <p>Runs on a Testcontainers Postgres like the sibling ITs, or — where Docker is unavailable — on
 * an existing empty database named by {@code NAVIX_IT_JDBC_URL} (+ {@code NAVIX_IT_DB_USER} /
 * {@code NAVIX_IT_DB_PASSWORD}); Flyway migrates it on boot either way.
 */
@SpringBootTest
@Tag("integration")
class CustomerBookQueryIntegrationTest {

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

    @Autowired CustomerBookQuery query;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    @BeforeEach
    void wipe() {
        for (String t : List.of("application_event", "customer_profile", "customer_owner", "loan", "loan_application")) {
            jdbc.update("delete from " + t);
        }
    }

    /** Verbatim port of {@code segmentOf()} in {@code frontend/src/lib/customers/segments.ts}. */
    private static String expectedSegment(String loan, String app) {
        if (loan != null && Set.of("OVERDUE", "IN_COLLECTIONS").contains(loan)) return "overdue";
        if (Set.of("OVERDUE", "DEFAULTED").contains(app)) return "overdue";
        if (loan != null && Set.of("ACTIVE", "DISBURSING").contains(loan)) return "active";
        if (Set.of("DISBURSED", "ACTIVE").contains(app)) return "active";
        if (Set.of("REVIEW_PENDING", "CREDIT_EXEC_PENDING", "CREDIT_HEAD_PENDING").contains(app)) return "review";
        if (Set.of("KYC_APPROVED", "CREDIT_EXEC_APPROVED", "CREDIT_HEAD_APPROVED", "SANCTIONED").contains(app)) {
            return "approved";
        }
        if (Set.of("DISBURSEMENT_PENDING", "ACCOUNTANT_PENDING").contains(app)) return "disbursementPending";
        if ("DISBURSEMENT_FAILED".equals(app)) return "hold";
        if ("DRAFT".equals(app)) return "incomplete";
        if (Set.of("KYC_REJECTED", "REJECTED").contains(app)) return "rejected";
        if (Set.of("CLOSED", "WRITTEN_OFF", "CANCELLED").contains(app)) return "closed";
        return "pending";
    }

    private static BookFilter filter(String needle, String seg) {
        return new BookFilter(needle, null, null, seg, null, false, null, TODAY);
    }

    private long application(long customerId, String status, Instant createdAt) {
        Long id = jdbc.queryForObject(
                "insert into loan_application (customer_id, status, created_at) values (?, ?, ?) returning id",
                Long.class, customerId, status, Timestamp.from(createdAt));
        return id == null ? -1 : id;
    }

    private void loan(long customerId, String status, LocalDate dueDate) {
        jdbc.update("insert into loan (customer_id, status, principal, total_repayable, outstanding, due_date, created_at)"
                        + " values (?, ?, 0, 0, 0, ?, now())",
                customerId, status, dueDate);
    }

    private void profile(long applicationId, String name, String pan, String mobile) {
        jdbc.update("insert into customer_profile (application_id, full_name, pan, mobile, created_at, created_by)"
                        + " values (?, ?, ?, ?, now(), 'test')",
                applicationId, name, pan, mobile);
    }

    private void transition(long applicationId, String from, String to, Instant at) {
        jdbc.update("insert into application_event (application_id, from_status, to_status, actor_id, action, at)"
                        + " values (?, ?, ?, '1', 'TEST', ?)",
                applicationId, from, to, Timestamp.from(at));
    }

    private void owner(long customerId, long staffId) {
        jdbc.update("insert into customer_owner (customer_id, owner_staff_id) values (?, ?)", customerId, staffId);
    }

    @Test
    void segmentCaseMatchesSegmentOfForEveryLoanAndApplicationStatusPair() {
        Instant t0 = Instant.parse("2026-09-01T00:00:00Z");
        Map<String, List<Long>> expectedBydSegment = new HashMap<>();
        long customerId = 100;
        List<String> loanStatuses = new ArrayList<>();
        loanStatuses.add(null);
        for (LoanStatus ls : LoanStatus.values()) {
            loanStatuses.add(ls.name());
        }
        for (String loan : loanStatuses) {
            for (ApplicationStatus app : ApplicationStatus.values()) {
                customerId++;
                application(customerId, app.name(), t0);
                if (loan != null) {
                    // Due tomorrow: an ACTIVE loan must read ACTIVE here, not OVERDUE.
                    loan(customerId, loan, TODAY.plusDays(1));
                }
                expectedBydSegment.computeIfAbsent(expectedSegment(loan, app.name()), k -> new ArrayList<>())
                        .add(customerId);
            }
        }
        long total = loanStatuses.size() * ApplicationStatus.values().length;

        assertThat(query.count(filter("", null))).isEqualTo(total);
        Map<String, Long> counted = new HashMap<>();
        for (SegmentCount c : query.segmentCounts(filter("", null))) {
            counted.put(c.segment(), c.count());
        }
        for (Map.Entry<String, List<Long>> e : expectedBydSegment.entrySet()) {
            assertThat(counted.getOrDefault(e.getKey(), 0L)).as("count of " + e.getKey())
                    .isEqualTo(e.getValue().size());
            assertThat(query.pageIds(filter("", e.getKey()), 0, 1000)).as("ids in " + e.getKey())
                    .containsExactlyInAnyOrderElementsOf(e.getValue());
        }
        assertThat(counted.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(total);
    }

    @Test
    void activeLoanPastDueReadsOverdueLikeLoanEffectiveStatus() {
        application(1, "ACTIVE", Instant.now());
        loan(1, "ACTIVE", TODAY.minusDays(1));
        application(2, "ACTIVE", Instant.now());
        loan(2, "ACTIVE", TODAY);           // due today is NOT yet overdue (asOf.isAfter(dueDate))

        assertThat(query.pageIds(filter("", "overdue"), 0, 10)).containsExactly(1L);
        assertThat(query.pageIds(filter("", "active"), 0, 10)).containsExactly(2L);
    }

    @Test
    void ordersByStageDateDescendingWithNullsLastAndPages() {
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        long a1 = application(1, "REJECTED", base);
        transition(a1, "CREDIT_EXEC_PENDING", "REJECTED", base.plus(1, ChronoUnit.DAYS));
        long a2 = application(2, "SANCTIONED", base);
        transition(a2, "CREDIT_EXEC_PENDING", "SANCTIONED", base.plus(5, ChronoUnit.DAYS));
        // Same-status audit rows are activity, not a stage change — must not move the date.
        transition(a2, "SANCTIONED", "SANCTIONED", base.plus(9, ChronoUnit.DAYS));
        long a3 = application(3, "KYC_PENDING", base.plus(3, ChronoUnit.DAYS));
        // No transition event at all: falls back to created_at (3 days), sorting between 2 and 1.
        assertThat(a3).isPositive();

        assertThat(query.pageIds(filter("", null), 0, 10)).containsExactly(2L, 3L, 1L);
        assertThat(query.pageIds(filter("", null), 0, 2)).containsExactly(2L, 3L);
        assertThat(query.pageIds(filter("", null), 2, 2)).containsExactly(1L);
        assertThat(query.count(filter("", null))).isEqualTo(3);
    }

    @Test
    void needleMatchesNamePanMobileCustomerIdAndAnyOlderApplicationId() {
        long old = application(7, "CLOSED", Instant.parse("2026-01-01T00:00:00Z"));
        long latest = application(7, "ACTIVE", Instant.parse("2026-06-01T00:00:00Z"));
        profile(latest, "Asha Rao", "ABCPR1234K", "9876543210");
        application(8, "DRAFT", Instant.now());

        assertThat(query.pageIds(filter("asha", null), 0, 10)).containsExactly(7L);
        assertThat(query.pageIds(filter("abcpr", null), 0, 10)).containsExactly(7L);
        assertThat(query.pageIds(filter("98765", null), 0, 10)).containsExactly(7L);
        assertThat(query.pageIds(filter(String.valueOf(old), null), 0, 10)).contains(7L);
        assertThat(query.pageIds(filter("zzz", null), 0, 10)).isEmpty();
        // A LIKE metacharacter in the search is a literal, not a wildcard.
        assertThat(query.pageIds(filter("%", null), 0, 10)).isEmpty();
    }

    @Test
    void dateWindowIsHalfOpenOnTheLatestApplicationsCreatedAt() {
        application(1, "DRAFT", Instant.parse("2026-09-01T10:00:00Z"));
        application(2, "DRAFT", Instant.parse("2026-09-02T10:00:00Z"));
        application(3, "DRAFT", Instant.parse("2026-09-03T10:00:00Z"));
        BookFilter window = new BookFilter("", Instant.parse("2026-09-02T00:00:00Z"),
                Instant.parse("2026-09-03T00:00:00Z"), null, null, false, null, TODAY);

        assertThat(query.pageIds(window, 0, 10)).containsExactly(2L);
    }

    @Test
    void scopeMineAndUnallocatedComposeLikeCustomerScopePermits() {
        application(1, "KYC_PENDING", Instant.now());
        owner(1, 5);
        application(2, "KYC_PENDING", Instant.now());
        owner(2, 6);
        application(3, "KYC_PENDING", Instant.now());          // nobody owns 3

        BookFilter scopedNoUnallocated = new BookFilter("", null, null, null, Set.of(1L), false, null, TODAY);
        assertThat(query.pageIds(scopedNoUnallocated, 0, 10)).containsExactly(1L);

        BookFilter telecaller = new BookFilter("", null, null, null, Set.of(1L), true, null, TODAY);
        assertThat(query.pageIds(telecaller, 0, 10)).containsExactlyInAnyOrder(1L, 3L);

        BookFilter mine = new BookFilter("", null, null, null, null, false, Set.of(2L, 3L), TODAY);
        assertThat(query.pageIds(mine, 0, 10)).containsExactlyInAnyOrder(2L, 3L);

        BookFilter mineWithinScope = new BookFilter("", null, null, null, Set.of(1L), true, Set.of(2L, 3L), TODAY);
        assertThat(query.pageIds(mineWithinScope, 0, 10)).containsExactly(3L);

        BookFilter nothing = new BookFilter("", null, null, null, Set.of(), false, null, TODAY);
        assertThat(query.count(nothing)).isZero();

        assertThat(query.pageIds(filter("", "unallocated"), 0, 10)).containsExactly(3L);
        SegmentCount pending = query.segmentCounts(filter("", null)).stream()
                .filter(c -> c.segment().equals("pending")).findFirst().orElseThrow();
        assertThat(pending.count()).isEqualTo(3);
        assertThat(pending.unallocated()).isEqualTo(1);
    }
}
