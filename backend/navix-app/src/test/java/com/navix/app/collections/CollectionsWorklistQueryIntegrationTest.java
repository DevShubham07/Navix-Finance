package com.navix.app.collections;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.collections.service.CollectionsWorklistQuery;
import com.navix.collections.service.CollectionsWorklistQuery.WorklistFilter;
import com.navix.collections.service.DpdCalculator;
import com.navix.common.query.FilterSpec;
import com.navix.common.query.SqlFacets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
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
 * The collections worklist query against a real Flyway-migrated Postgres. The headline test is the
 * move's regression: for one saved filter set, the loans the server pages are exactly the loans the
 * old client-side register showed (the legacy rules are re-stated below over the same seeded rows,
 * independently of the SQL), and an executive's checklists carry nothing from outside their cases.
 * Same database wiring as {@code CustomerBookQueryFilterIntegrationTest}.
 */
@SpringBootTest
@Tag("integration")
class CollectionsWorklistQueryIntegrationTest {

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

    @Autowired CollectionsWorklistQuery query;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);
    private static final DpdCalculator DPD = new DpdCalculator();

    /** What the test seeded, kept so the legacy semantics can be evaluated without SQL. */
    record Seed(long loanId, long customerId, String name, String employer, long principalPaise, long salaryPaise,
            LocalDate due, String status, String officerName, Long officerId) {
        int dpd() {
            return DPD.daysPastDue(due, TODAY);
        }

        String bucket() {
            return DPD.bucket(dpd()).name();
        }
    }

    private final List<Seed> seeded = new ArrayList<>();
    private long meera;
    private long ravi;

    @BeforeEach
    void seed() {
        for (String t : List.of("collection_case", "customer_profile", "loan", "loan_application", "staff_user")) {
            jdbc.update("delete from " + t);
        }
        seeded.clear();
        meera = staff("Meera Krishnan", "meera@t.example");
        ravi = staff("Ravi Nair", "ravi@t.example");
        //            id  name            employer       principal salary   due offset  status           officer
        loan(1, "Asha Rao", "Acme Corp", 1_000_000, 4_000_000, 20, "ACTIVE", null);       // beyond the 7-day horizon: not collectible
        loan(2, "Bina Shah", "Acme Corp", 1_500_000, 5_000_000, 5, "ACTIVE", meera);      // pre-due (due in 5)
        loan(3, "Chetan Das", "Zenith Ltd", 2_000_000, 6_000_000, 0, "ACTIVE", ravi);     // due today -> UPCOMING
        loan(4, "Dev Patel", "Acme Corp", 2_500_000, 7_000_000, -3, "OVERDUE", meera);   // T0_T7
        loan(5, "Esha Iyer", "Zenith Ltd", 3_000_000, 8_000_000, -12, "IN_COLLECTIONS", null); // T8_T30, no case
        loan(6, "Farid Khan", "Acme Corp", 3_500_000, 9_000_000, -45, "IN_COLLECTIONS", ravi); // T30_T60
        loan(7, "Gita Rao", "Orbit Inc", 4_000_000, 3_000_000, -75, "OVERDUE", meera);   // T60_T90
        loan(8, "Hari Om", "Orbit Inc", 4_500_000, 3_500_000, -120, "OVERDUE", null);    // T90_PLUS, no case
        loan(9, "Isha Bose", "Acme Corp", 5_000_000, 2_000_000, -20, "CLOSED", null);    // settled: never listed
        seeded.removeIf(s -> s.status().equals("CLOSED") || s.due().isAfter(TODAY.plusDays(7)));
    }

    private long staff(String name, String email) {
        return jdbc.queryForObject("insert into staff_user (email, name, role, status, created_at, created_by)"
                + " values (?, ?, 'COLLECTION_EXECUTIVE', 'ACTIVE', now(), 'test') returning id", Long.class, email, name);
    }

    private void loan(long customerId, String name, String employer, long principal, long salary, int dueOffset,
            String status, Long officer) {
        LocalDate due = TODAY.plusDays(dueOffset);
        long loanId = jdbc.queryForObject("insert into loan (customer_id, status, principal, total_repayable, outstanding,"
                + " due_date, created_at) values (?, ?, ?, 0, 0, ?, now()) returning id",
                Long.class, customerId, status, principal, due);
        long app = jdbc.queryForObject("insert into loan_application (customer_id, status, created_at, loan_id)"
                + " values (?, 'ACTIVE', now(), ?) returning id", Long.class, customerId, loanId);
        jdbc.update("insert into customer_profile (application_id, full_name, pan, mobile, employer, monthly_salary_paise,"
                + " created_at, created_by) values (?, ?, ?, ?, ?, ?, now(), 'test')",
                app, name, "ABCDE" + (1000 + customerId) + "F", "90000000" + customerId, employer, salary);
        if (officer != null) {
            jdbc.update("insert into collection_case (id, loan_id, assigned_officer_id, created_at) values (?, ?, ?, now())",
                    UUID.randomUUID(), loanId, officer);
        }
        String officerName = officer == null ? null : officer == meera ? "Meera Krishnan" : "Ravi Nair";
        seeded.add(new Seed(loanId, customerId, name, employer, principal, salary, due, status, officerName, officer));
    }

    private static WorklistFilter head(String bucket) {
        return new WorklistFilter(null, null, null, bucket, false, null, TODAY, TODAY);
    }

    private static FilterSpec spec(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return FilterSpec.parse(m, CollectionsWorklistQuery.FILTER_COLUMNS);
    }

    private List<Long> expected(Predicate<Seed> legacyRule) {
        return seeded.stream().filter(legacyRule)
                .sorted(Comparator.comparingInt(Seed::dpd).reversed().thenComparing(Comparator.comparingLong(Seed::loanId).reversed()))
                .map(Seed::loanId).toList();
    }

    /**
     * THE saved filter set: All loans, employer contains "acme", principal Rs 15,000 or more, collections
     * exec = Meera. Before the move the browser applied these to the whole worklist; now SQL does. Same rows,
     * same count, same order.
     */
    @Test
    void aSavedFilterSetShowsTheSameRowsAndCountAsTheOldClientRegister() {
        FilterSpec saved = spec("f.employer", "text:contains:acme", "f.principalPaise", "num:15000..",
                "f.officerName", "in:" + meera);
        List<Long> legacy = expected(s -> s.employer().toLowerCase().contains("acme")
                && s.principalPaise() >= 15_000L * 100 && "Meera Krishnan".equals(s.officerName()));

        assertThat(legacy).as("the fixture must actually exercise the filters").hasSize(2);
        assertThat(query.pageIds(head(null), saved, 0, 100, "dpd", "desc")).isEqualTo(legacy);
        assertThat(query.count(head(null), saved)).isEqualTo(legacy.size());
        // And a page is a slice of it, not a different set.
        assertThat(query.pageIds(head(null), saved, 1, 1, "dpd", "desc")).containsExactly(legacy.get(1));
    }

    @Test
    void theCollectibleSetAndBucketsMatchTheDpdCalculator() {
        // Closed and beyond-horizon loans never list; every bucket assignment agrees with DpdCalculator.
        assertThat(query.pageIds(head(null), FilterSpec.empty(), 0, 100, null, null))
                .isEqualTo(expected(s -> true));
        for (String bucket : List.of("UPCOMING", "T0_T7", "T8_T30", "T30_T60", "T60_T90", "T90_PLUS")) {
            assertThat(query.pageIds(head(bucket), FilterSpec.empty(), 0, 100, null, null))
                    .as(bucket).isEqualTo(expected(s -> s.bucket().equals(bucket)));
        }
    }

    @Test
    void theBucketColumnFilterAndTheDpdFilterAgreeWithTheClientRules() {
        assertThat(query.pageIds(head(null), spec("f.bucketLabel", "in:T0_T7,T90_PLUS"), 0, 100, "loanId", "asc"))
                .containsExactlyInAnyOrderElementsOf(expected(s -> List.of("T0_T7", "T90_PLUS").contains(s.bucket())));
        assertThat(query.pageIds(head(null), spec("f.dpd", "num:10..50"), 0, 100, "dpd", "asc"))
                .containsExactlyInAnyOrderElementsOf(expected(s -> s.dpd() >= 10 && s.dpd() <= 50));
        // A day window over the due date (IST days, inclusive).
        String d = TODAY.minusDays(60) + ".." + TODAY.minusDays(3);
        assertThat(query.pageIds(head(null), spec("f.dueDate", "date:" + d), 0, 100, "dpd", "asc"))
                .containsExactlyInAnyOrderElementsOf(expected(s -> !s.due().isBefore(TODAY.minusDays(60))
                        && !s.due().isAfter(TODAY.minusDays(3))));
    }

    @Test
    void searchMatchesIdNameMobileEmployerOfficerAndLoanNumber() {
        for (String needle : List.of("asha", "ACME", "meera", "9000000004", String.valueOf(seeded.get(2).loanId()))) {
            WorklistFilter f = new WorklistFilter(needle, null, null, null, false, null, TODAY, TODAY);
            String n = needle.toLowerCase();
            assertThat(query.pageIds(f, FilterSpec.empty(), 0, 100, null, null)).as(needle)
                    .isEqualTo(expected(s -> (s.customerId() + " " + s.name() + " 90000000" + s.customerId() + " " + s.employer()
                            + " " + s.officerName() + " " + s.loanId()).toLowerCase().contains(n)));
        }
    }

    @Test
    void anExecutiveSeesOnlyTheirOwnCasesAndTheirChecklistsOfferNothingElse() {
        WorklistFilter mine = new WorklistFilter(null, null, null, null, true, ravi, TODAY, TODAY);
        List<Long> ravisLoans = expected(s -> s.officerId() != null && s.officerId() == ravi);
        assertThat(query.pageIds(mine, FilterSpec.empty(), 0, 100, null, null)).isEqualTo(ravisLoans);
        assertThat(query.count(mine, FilterSpec.empty())).isEqualTo(ravisLoans.size());

        // A filter that names another officer can only narrow Ravi's own set to nothing — it cannot reach Meera's.
        assertThat(query.pageIds(mine, spec("f.officerName", "in:" + meera), 0, 100, null, null)).isEmpty();

        SqlFacets.Facet officers = query.facet(mine, FilterSpec.empty(), "officerName");
        assertThat(officers.values()).containsExactly(String.valueOf(ravi));
        SqlFacets.Facet buckets = query.facet(mine, FilterSpec.empty(), "bucketLabel");
        assertThat(buckets.values()).containsExactlyInAnyOrderElementsOf(
                seeded.stream().filter(s -> s.officerId() != null && s.officerId() == ravi).map(Seed::bucket).distinct().toList());

        // The Head's checklist, by contrast, lists every officer plus Unallocated.
        assertThat(query.facet(head(null), FilterSpec.empty(), "officerName").values())
                .containsExactlyInAnyOrder(String.valueOf(meera), String.valueOf(ravi), "__NULL__");
    }

    @Test
    void sortingByAStoredColumnOrdersInSql() {
        assertThat(query.pageIds(head(null), FilterSpec.empty(), 0, 100, "principalPaise", "asc"))
                .isEqualTo(seeded.stream().sorted(Comparator.comparingLong(Seed::principalPaise).thenComparingLong(Seed::loanId))
                        .map(Seed::loanId).toList());
        assertThat(query.pageIds(head(null), FilterSpec.empty(), 0, 100, "borrowerName", "desc"))
                .isEqualTo(seeded.stream().sorted(Comparator.comparing((Seed s) -> s.name().toLowerCase()).reversed())
                        .map(Seed::loanId).toList());
    }
}
