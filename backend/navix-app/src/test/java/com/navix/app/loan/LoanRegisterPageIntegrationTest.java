package com.navix.app.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.LoanRegisterDtos.LoanRegisterRow;
import com.navix.loan.service.LoanRegisterService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
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
 * The client-to-server move of the Loans register, proven against a real Flyway-migrated Postgres:
 * for a saved filter set the PAGED path must return exactly the rows, in exactly the order, with
 * exactly the total, that the OLD path (enrich every loan, then narrow in Java) produced.
 *
 * <p>The oracle is {@link LoanRegisterService#list}, untouched, with each filter re-stated here as a
 * plain Java predicate over the row it returns. If a SQL twin (effective status, DPD, loan cycle, the
 * IST day bounds, the rupees-to-paise scale) drifts from the Java it mirrors, one of these fails.
 * Same database wiring as {@code CustomerBookQueryFilterIntegrationTest}.
 */
@SpringBootTest
@Tag("integration")
class LoanRegisterPageIntegrationTest {

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

    @Autowired LoanRegisterService service;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.now();

    @BeforeEach
    void seed() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        for (String t : List.of("payment", "collection_case", "customer_profile", "loan_application", "loan")) {
            jdbc.update("delete from " + t);
        }
        // customer, name, principal, disbursed (days ago), due (days from today), closed (days ago, or null), status
        loan(1, "Asha Verma", 1_000_000, 60, -20, null, "ACTIVE");        // overdue 20d (stored ACTIVE)
        loan(1, "Asha Verma", 2_000_000, 30, 10, null, "ACTIVE");         // live, 2nd advance
        loan(2, "Bela Rao", 500_000, 90, -50, 45, "CLOSED");              // closed
        loan(3, "Chetan Das", 1_500_000, 45, -5, null, "IN_COLLECTIONS"); // stored collections
        loan(4, "Dev Anand", 2_500_000, 20, 15, null, "ACTIVE");
        loan(4, "Dev Anand", 700_000, 100, -70, 70, "REPAID");
        loan(4, "Dev Anand", 3_000_000, 5, 25, null, "ACTIVE");          // 3rd advance
        loan(5, "Esha Nair", 1_200_000, 40, 0, null, "ACTIVE");          // due today: not overdue
        loan(6, null, 900_000, 12, 18, null, "ACTIVE");                  // no application/profile at all
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private void loan(long customer, String name, long principal, int disbursedAgo, int dueIn, Integer closedAgo,
            String status) {
        Long id = jdbc.queryForObject(
                "insert into loan (customer_id, principal, net_disbursed, total_repayable, disbursed_on, due_date, closed_on,"
                        + " status, created_at, created_by) values (?, ?, ?, ?, ?, ?, ?, ?, now(), 'test') returning id",
                Long.class, customer, principal, principal * 882 / 1000, principal * 127 / 100,
                java.sql.Date.valueOf(TODAY.minusDays(disbursedAgo)), java.sql.Date.valueOf(TODAY.plusDays(dueIn)),
                closedAgo == null ? null : java.sql.Date.valueOf(TODAY.minusDays(closedAgo)), status);
        if (name == null) {
            return;
        }
        Long app = jdbc.queryForObject(
                "insert into loan_application (customer_id, status, loan_id, sanctioned_at, created_at)"
                        + " values (?, 'ACTIVE', ?, ?, now()) returning id",
                Long.class, customer, id, Timestamp.from(Instant.now().minusSeconds(86_400L * (disbursedAgo + 3))));
        jdbc.update("insert into customer_profile (application_id, full_name, pan, mobile, created_at, created_by)"
                + " values (?, ?, ?, ?, now(), 'test')", app, name, "PAN" + customer + "0000A", "90000000" + customer + "0");
    }

    /** The old behaviour: the whole window, enriched, then narrowed and sorted by hand. */
    private List<LoanRegisterRow> oldPath(String q, LocalDate from, LocalDate to, Predicate<LoanRegisterRow> keep,
            Comparator<LoanRegisterRow> order) {
        List<LoanRegisterRow> rows = new ArrayList<>(service.list(null, q, from, to).stream().filter(keep).toList());
        rows.sort(order);
        return rows;
    }

    private static final Comparator<LoanRegisterRow> DUE_DESC =
            Comparator.comparing(LoanRegisterRow::dueDate).reversed().thenComparing(
                    Comparator.comparing(LoanRegisterRow::loanId).reversed());

    private static String segmentOf(String status) {
        return switch (status) {
            case "OVERDUE", "IN_COLLECTIONS" -> "overdue";
            case "REPAID", "CLOSED" -> "closed";
            default -> "active";
        };
    }

    private List<Long> allPages(String q, String seg, LocalDate from, LocalDate to, Map<String, String> filters,
            String sort, String dir, int size, long expectedTotal) {
        List<Long> seen = new ArrayList<>();
        for (int page = 1; seen.size() < expectedTotal; page++) {
            LoanRegisterService.Page p = service.page(q, seg, from, to, filters, sort, dir, page, size);
            assertThat(p.total()).as("total on page " + page).isEqualTo(expectedTotal);
            assertThat(p.rows()).hasSizeLessThanOrEqualTo(size);
            if (p.rows().isEmpty()) {
                break;
            }
            p.rows().forEach(r -> seen.add(r.loanId()));
        }
        return seen;
    }

    @Test
    void anUnfilteredRegisterPagesToExactlyTheOldList() {
        List<LoanRegisterRow> old = oldPath(null, null, null, r -> true, DUE_DESC);

        assertThat(old).hasSize(9);
        assertThat(allPages(null, null, null, null, Map.of(), null, null, 4, old.size()))
                .isEqualTo(old.stream().map(LoanRegisterRow::loanId).toList());
        // Every enriched field, not just the ids: the page is the same row the old list built.
        LoanRegisterService.Page first = service.page(null, null, null, null, Map.of(), null, null, 1, 100);
        assertThat(first.rows()).isEqualTo(old);
    }

    @Test
    void aSavedFilterSetShowsTheSameRowsAndTheSameCount() {
        // status in (OVERDUE, CLOSED) AND principal >= Rs 5,000 (typed in rupees) AND borrower contains "a"
        // AND disbursed in the last 80 days AND dpd >= 1 — one filter per kind, over derived columns too.
        Map<String, String> saved = Map.of(
                "f.status", "in:OVERDUE,CLOSED,IN_COLLECTIONS",
                "f.principalPaise", "num:5000..",
                "f.borrowerName", "text:contains:a",
                "f.disbursedOn", "date:" + TODAY.minusDays(80) + ".." + TODAY,
                "f.dpd", "num:1..");
        Predicate<LoanRegisterRow> keep = r -> List.of("OVERDUE", "CLOSED", "IN_COLLECTIONS").contains(r.status())
                && r.principalPaise() >= 500_000
                && r.borrowerName() != null && r.borrowerName().toLowerCase().contains("a")
                && !r.disbursedOn().isBefore(TODAY.minusDays(80)) && !r.disbursedOn().isAfter(TODAY)
                && r.dpd() >= 1;
        List<LoanRegisterRow> old = oldPath(null, null, null, keep, DUE_DESC);

        assertThat(old).isNotEmpty().hasSizeLessThan(9);
        assertThat(allPages(null, null, null, null, saved, null, null, 2, old.size()))
                .isEqualTo(old.stream().map(LoanRegisterRow::loanId).toList());
    }

    @Test
    void theSegmentChipFollowsTheEffectiveStatusNotTheStoredOne() {
        for (String seg : List.of("active", "overdue", "closed")) {
            List<LoanRegisterRow> old = oldPath(null, null, null, r -> segmentOf(r.status()).equals(seg), DUE_DESC);
            assertThat(old).as(seg).isNotEmpty();
            assertThat(allPages(null, seg, null, null, Map.of(), null, null, 3, old.size()))
                    .as(seg).isEqualTo(old.stream().map(LoanRegisterRow::loanId).toList());
        }
        // The one stored-ACTIVE loan that is 20 days late is overdue, as Loan.effectiveStatus says.
        assertThat(service.page(null, "overdue", null, null, Map.of(), null, null, 1, 100).total()).isEqualTo(2);
    }

    @Test
    void theDateWindowAndSearchNarrowFirstAndLoanCycleIsOverTheWindow() {
        LocalDate from = TODAY.minusDays(50);
        List<LoanRegisterRow> old = oldPath("dev", from, null, r -> true, DUE_DESC);

        LoanRegisterService.Page p = service.page("dev", null, from, null, Map.of(), null, null, 1, 100);

        assertThat(p.total()).isEqualTo(old.size());
        assertThat(p.rows()).isEqualTo(old);
        // Dev's 100-days-ago loan is outside the window, so his 20- and 5-day-old loans are cycles 1 and 2.
        assertThat(p.rows()).extracting(LoanRegisterRow::loanCycle).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void sortingBySqlColumnsAndByJavaColumnsBothMatchTheOldOrder() {
        List<LoanRegisterRow> byPrincipal = oldPath(null, null, null, r -> true,
                Comparator.comparing(LoanRegisterRow::principalPaise).thenComparing(LoanRegisterRow::loanId));
        assertThat(allPages(null, null, null, null, Map.of(), "principalPaise", "asc", 4, 9))
                .isEqualTo(byPrincipal.stream().map(LoanRegisterRow::loanId).toList());

        // outstanding is Java-side: filter AND sort, over the ids SQL narrowed.
        List<LoanRegisterRow> big = oldPath(null, null, null, r -> r.outstandingPaise() >= 1_500_000,
                Comparator.comparing(LoanRegisterRow::outstandingPaise).reversed().thenComparing(DUE_DESC));
        assertThat(allPages(null, null, null, null, Map.of("f.outstandingPaise", "num:15000.."),
                "outstandingPaise", "desc", 2, big.size()))
                .isEqualTo(big.stream().map(LoanRegisterRow::loanId).toList());
    }

    @Test
    void aStatusFacetOffersTheEffectiveStatusesInsideTheOtherFiltersAndAnIdentityColumnHasNone() {
        assertThat(service.facet("status", null, null, null, null, Map.of()).values())
                .containsExactly("ACTIVE", "CLOSED", "IN_COLLECTIONS", "OVERDUE", "REPAID");
        // The chip narrows what is worth offering: inside "closed" only the closed statuses are choices.
        assertThat(service.facet("status", null, "closed", null, null, Map.of()).values())
                .containsExactly("CLOSED", "REPAID");
        // The column's own filter is dropped, so you can still switch; another column's narrows.
        assertThat(service.facet("status", null, null, null, null, Map.of("f.status", "in:ACTIVE")).values())
                .hasSize(5);
        assertThat(service.facet("status", null, null, null, null, Map.of("f.principalPaise", "num:25000..")).values())
                .containsExactly("ACTIVE");
        // Name is TEXT only: there is no dropdown of borrowers to ask for.
        assertThat(service.facet("borrowerName", null, null, null, null, Map.of()).values()).isEmpty();
    }
}
