package com.navix.app.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.query.FilterSpec;
import com.navix.common.query.SqlFacets;
import com.navix.loan.service.CustomerBookQuery;
import com.navix.loan.service.CustomerBookQuery.BookFilter;
import com.navix.loan.service.CustomerBookQuery.SegmentCount;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 * The column-filter engine wired onto the customer book, against a real Flyway-migrated Postgres:
 * a filter narrows the page and the count by the same clauses, a facet runs inside the caller's
 * scope (no enumeration oracle), and a filter can never widen a scoped result.
 * Same database wiring as {@link CustomerBookQueryIntegrationTest}.
 */
@SpringBootTest
@Tag("integration")
class CustomerBookQueryFilterIntegrationTest {

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
        // 1 Asha (REJECTED -> rejected, owned by staff 5), 2 Bina (ACTIVE -> active, staff 6),
        // 3 Chetan (DRAFT -> incomplete, nobody), 4 Asha Verma (REJECTED, staff 6).
        customer(1, "REJECTED", "Asha Rao", "AAAAA1111A", "9000000001", "2026-09-01T10:00:00Z");
        owner(1, 5);
        customer(2, "ACTIVE", "Bina Shah", "BBBBB1111B", "9000000002", "2026-09-02T10:00:00Z");
        owner(2, 6);
        customer(3, "DRAFT", "Chetan Das", "CCCCC1111C", "9000000003", "2026-09-03T10:00:00Z");
        customer(4, "REJECTED", "Asha Verma", "DDDDD1111D", "9000000004", "2026-09-04T10:00:00Z");
        owner(4, 6);
    }

    private void customer(long id, String status, String name, String pan, String mobile, String createdAt) {
        Long app = jdbc.queryForObject(
                "insert into loan_application (customer_id, status, created_at) values (?, ?, ?) returning id",
                Long.class, id, status, Timestamp.from(Instant.parse(createdAt)));
        jdbc.update("insert into customer_profile (application_id, full_name, pan, mobile, created_at, created_by)"
                + " values (?, ?, ?, ?, now(), 'test')", app, name, pan, mobile);
    }

    private void owner(long customerId, long staffId) {
        jdbc.update("insert into customer_owner (customer_id, owner_staff_id) values (?, ?)", customerId, staffId);
    }

    private static FilterSpec spec(String... kv) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return FilterSpec.parse(m, CustomerBookQuery.FILTER_COLUMNS);
    }

    private static BookFilter all() {
        return new BookFilter("", null, null, null, null, false, null, TODAY);
    }

    private static BookFilter scopedTo(Set<Long> ids) {
        return new BookFilter("", null, null, null, ids, false, null, TODAY);
    }

    private List<Long> ids(BookFilter f, FilterSpec s) {
        return query.pageIds(f, 0, 100, "customerId", "asc", s);
    }

    @Test
    void aFilterNarrowsThePageAndTheCountByTheSameClauses() {
        for (FilterSpec s : List.of(
                spec("f.name", "text:contains:asha"),
                spec("f.stage", "in:rejected"),
                spec("f.stage", "in:rejected,active", "f.name", "text:starts:asha"),
                spec("f.mobile", "text:eq:9000000003"),
                spec("f.pan", "text:starts:bbb"),
                spec("f.date", "date:2026-09-02..2026-09-03"),
                spec("f.stageDate", "date:2026-09-04.."),
                spec("f.name", "text:contains:zzz"))) {
            assertThat(query.count(all(), s)).as(s.filters().toString()).isEqualTo(ids(all(), s).size());
        }
        assertThat(ids(all(), spec("f.name", "text:contains:asha"))).containsExactly(1L, 4L);
        assertThat(ids(all(), spec("f.stage", "in:rejected"))).containsExactly(1L, 4L);
        assertThat(ids(all(), spec("f.stage", "in:rejected,active", "f.name", "text:starts:asha"))).containsExactly(1L, 4L);
        assertThat(ids(all(), spec("f.mobile", "text:eq:9000000003"))).containsExactly(3L);
        assertThat(ids(all(), spec("f.pan", "text:starts:bbb"))).containsExactly(2L);
        assertThat(ids(all(), spec("f.date", "date:2026-09-02..2026-09-03"))).containsExactly(2L, 3L);
        assertThat(ids(all(), spec("f.name", "text:contains:zzz"))).isEmpty();
        // A LIKE metacharacter typed into a text filter is a literal.
        assertThat(ids(all(), spec("f.name", "text:contains:%"))).isEmpty();
        // No filter = the old behaviour, untouched.
        assertThat(ids(all(), FilterSpec.empty())).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void aFilterAndTheSegmentChipAndSearchComposeAsAnd() {
        BookFilter rejectedChip = new BookFilter("", null, null, "rejected", null, false, null, TODAY);
        assertThat(ids(rejectedChip, spec("f.name", "text:contains:verma"))).containsExactly(4L);
        BookFilter searched = new BookFilter("asha", null, null, null, null, false, null, TODAY);
        assertThat(ids(searched, spec("f.pan", "text:starts:ddd"))).containsExactly(4L);
        assertThat(query.count(searched, spec("f.pan", "text:starts:ddd"))).isEqualTo(1);
    }

    @Test
    void aFilterCannotWidenAScopedResult() {
        BookFilter staff5 = scopedTo(Set.of(1L));
        // Customer 2 exists and matches the filter, but is outside scope: it must not appear.
        assertThat(ids(staff5, spec("f.name", "text:contains:bina"))).isEmpty();
        assertThat(query.count(staff5, spec("f.name", "text:contains:bina"))).isZero();
        // Even a filter that matches the whole book returns only the scope.
        assertThat(ids(staff5, spec("f.stage", "in:rejected,active,incomplete"))).containsExactly(1L);
        // Hostile input stays a bound literal.
        assertThat(ids(staff5, spec("f.name", "text:contains:x' or 1=1 --"))).isEmpty();
        // Scope columns are not in the registry, so a request cannot even name them.
        assertThat(spec("f.customer_id", "in:2", "f.owner_staff_id", "in:6", "f.scopeIds", "in:2").isEmpty()).isTrue();
        assertThat(ids(staff5, spec("f.customer_id", "in:2", "f.owner_staff_id", "in:6"))).containsExactly(1L);
        // "Mine" is a restriction too: filtering cannot step outside it.
        BookFilter mine = new BookFilter("", null, null, null, null, false, Set.of(2L), TODAY);
        assertThat(ids(mine, spec("f.name", "text:contains:asha"))).isEmpty();
    }

    @Test
    void facetsRunInsideTheCallersScope() {
        // Whole book: every stage present.
        assertThat(query.facet(all(), FilterSpec.empty(), "stage").values())
                .containsExactlyInAnyOrder("rejected", "active", "incomplete");
        // Staff 5 owns only customer 1 (rejected): nothing from outside that book is offered.
        SqlFacets.Facet scoped = query.facet(scopedTo(Set.of(1L)), FilterSpec.empty(), "stage");
        assertThat(scoped.values()).containsExactly("rejected");
        assertThat(scoped.truncated()).isFalse();
        // The same scope that lets a telecaller see unallocated customers lets their stage in, no more.
        BookFilter telecaller = new BookFilter("", null, null, null, Set.of(1L), true, null, TODAY);
        assertThat(query.facet(telecaller, FilterSpec.empty(), "stage").values())
                .containsExactlyInAnyOrder("rejected", "incomplete");
        // An empty scope sees nothing, not everything.
        assertThat(query.facet(scopedTo(Set.of()), FilterSpec.empty(), "stage").values()).isEmpty();
        // The search / date window narrow it too, as they narrow the list.
        BookFilter searched = new BookFilter("bina", null, null, null, null, false, null, TODAY);
        assertThat(query.facet(searched, FilterSpec.empty(), "stage").values()).containsExactly("active");
    }

    @Test
    void facetsHonourOtherFiltersButNotTheirOwn() {
        FilterSpec s = spec("f.stage", "in:active", "f.name", "text:contains:asha");
        // Own filter (stage=active) is dropped so the dropdown can still switch; name still narrows.
        assertThat(query.facet(all(), s.without("stage"), "stage").values()).containsExactly("rejected");
        assertThat(query.facet(all(), s, "stage").values()).containsExactly("rejected");
        // Identity columns have no facet: a TEXT-only column yields no query and no values.
        assertThat(query.facet(all(), FilterSpec.empty(), "name").values()).isEmpty();
        assertThat(query.facet(all(), FilterSpec.empty(), "pan").values()).isEmpty();
        assertThat(query.facet(all(), FilterSpec.empty(), "1; drop table loan").values()).isEmpty();
    }

    @Test
    void segmentChipsFollowColumnFiltersButNotTheStageFilter() {
        Map<String, Long> unfiltered = counts(all(), FilterSpec.empty());
        assertThat(unfiltered).containsEntry("rejected", 2L).containsEntry("active", 1L).containsEntry("incomplete", 1L);

        // A name filter narrows the chips like search does.
        assertThat(counts(all(), spec("f.name", "text:contains:asha"))).containsOnly(Map.entry("rejected", 2L));
        // A stage filter does not collapse them: the chips ARE the stage dimension.
        assertThat(counts(all(), spec("f.stage", "in:active"))).isEqualTo(unfiltered);
        // Scope still bounds them.
        assertThat(counts(scopedTo(Set.of(1L)), spec("f.name", "text:contains:bina"))).isEmpty();
    }

    private Map<String, Long> counts(BookFilter f, FilterSpec s) {
        Map<String, Long> out = new java.util.HashMap<>();
        for (SegmentCount c : query.segmentCounts(f, s)) {
            out.put(c.segment(), c.count());
        }
        return out;
    }
}
