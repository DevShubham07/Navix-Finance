package com.navix.app.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.query.FilterSpec;
import com.navix.common.query.SqlFacets;
import com.navix.loan.dto.LoanDtos.TransactionView;
import com.navix.loan.service.TransactionLedgerQuery;
import com.navix.loan.service.TransactionLedgerQuery.LedgerFilter;
import com.navix.loan.service.TransactionLedgerQuery.Totals;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * The transactions ledger's native union against a real Flyway-migrated Postgres. The expected rows
 * and totals below are exactly what the in-memory merge it replaced produced for the same data (the
 * ledger tests in {@code TransactionServiceTest} were written against that): the money assertions
 * are the point — a REJECTED repayment is in neither the rows nor any total, and the totals follow
 * the whole filter, not the page. Same database wiring as {@code CustomerBookQueryFilterIntegrationTest}.
 */
@SpringBootTest
@Tag("integration")
class TransactionLedgerQueryIntegrationTest {

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

    @Autowired TransactionLedgerQuery query;
    @Autowired JdbcTemplate jdbc;

    private static final LedgerFilter ALL = new LedgerFilter(null, null, null, null);

    @BeforeEach
    void seed() {
        for (String t : List.of("payment", "customer_profile", "loan_application", "loan")) {
            jdbc.update("delete from " + t);
        }
        // Loan 2 (Aman) disbursed 20 May, loan 3 (Bela) disbursed 10 Jun; ids pinned so the row ids are stable.
        loan(2, 7, 882_000, "UTR-OUT-1", "2026-05-20", "2026-06-30");
        loan(3, 8, 500_000, "UTR-OUT-2", "2026-06-10", "2026-07-10");
        profile(2, 7, "Aman Rao", "ABCDE1234F", "9876543210");
        profile(3, 8, "Bela Shah", "FGHIJ5678K", "9123456780");
        payment(1, 2, 100_000, "VERIFIED", "PAY-IN-1", "2026-06-20");
        payment(2, 2, 500_000, "REJECTED", "PAY-BAD", "2026-06-21");   // never moved money
        payment(3, 2, 250_000, "PENDING_VERIFICATION", null, "2026-06-22");
        payment(4, 3, 40_000, "VERIFIED", "PAY-IN-4", "2026-07-01");
    }

    private void loan(long id, long customer, long net, String ref, String disbursedOn, String due) {
        jdbc.update("insert into loan (id, customer_id, principal, net_disbursed, disbursal_txn_ref, disbursed_on,"
                + " due_date, status, created_at) values (?, ?, ?, ?, ?, ?::date, ?::date, 'ACTIVE', now())",
                id, customer, net, net, ref, disbursedOn, due);
    }

    private void profile(long loanId, long customer, String name, String pan, String mobile) {
        Long app = jdbc.queryForObject(
                "insert into loan_application (customer_id, status, loan_id, created_at) values (?, 'ACTIVE', ?, ?) returning id",
                Long.class, customer, loanId, Timestamp.from(Instant.parse("2026-05-01T10:00:00Z")));
        jdbc.update("insert into customer_profile (application_id, full_name, pan, mobile, created_at, created_by)"
                + " values (?, ?, ?, ?, now(), 'test')", app, name, pan, mobile);
    }

    private void payment(long id, long loanId, long amount, String status, String ref, String paidOn) {
        jdbc.update("insert into payment (id, loan_id, amount, method, status, txn_ref, paid_on, partial, created_at)"
                + " values (?, ?, ?, 'UPI', ?, ?, ?::date, false, now())", id, loanId, amount, status, ref, paidOn);
    }

    private static FilterSpec spec(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return FilterSpec.parse(m, TransactionLedgerQuery.FILTER_COLUMNS);
    }

    private List<String> ids(LedgerFilter f, FilterSpec s) {
        return query.page(f, s, 0, 100, null, null).stream().map(TransactionView::id).toList();
    }

    @Test
    void aRejectedRepaymentIsInNeitherTheRowsNorAnyTotal() {
        assertThat(ids(ALL, FilterSpec.empty())).doesNotContain("P-2");
        assertThat(ids(ALL, FilterSpec.empty())).containsExactlyInAnyOrder("D-2", "D-3", "P-1", "P-3", "P-4");

        Totals t = query.totals(ALL, FilterSpec.empty());
        assertThat(t.count()).isEqualTo(5);
        assertThat(t.inPaise()).isEqualTo(140_000L);          // P-1 + P-4, verified only
        assertThat(t.pendingInPaise()).isEqualTo(250_000L);   // P-3 listed, not inflow
        assertThat(t.outPaise()).isEqualTo(1_382_000L);       // both disbursals
        // The rejected ₹5,000 would have shown up here had it leaked in.
        assertThat(t.inPaise() + t.pendingInPaise() + t.outPaise()).isEqualTo(1_772_000L);
    }

    @Test
    void rowsCarryTheSameFieldsTheOldMergeBuilt() {
        TransactionView d = query.page(ALL, spec("f.loan", "text:eq:2", "f.type", "in:DISBURSAL"), 0, 10, null, null).get(0);
        assertThat(d.id()).isEqualTo("D-2");
        assertThat(d.direction()).isEqualTo("OUTGOING");
        assertThat(d.amountPaise()).isEqualTo(882_000L);
        assertThat(d.txnRef()).isEqualTo("UTR-OUT-1");
        assertThat(d.borrowerName()).isEqualTo("Aman Rao");
        assertThat(d.pan()).isEqualTo("ABCDE1234F");     // staff ledger: the full PAN
        assertThat(d.customerId()).isEqualTo(7L);
        assertThat(d.status()).isEqualTo("ACTIVE");      // a LoanStatus on a disbursal row
        assertThat(d.date()).isEqualTo(LocalDate.of(2026, 5, 20));
        assertThat(d.repaymentDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(d.proofUrl()).isNull();

        TransactionView r = query.page(ALL, spec("f.reference", "text:eq:pay-in-1"), 0, 10, null, null).get(0);
        assertThat(r.id()).isEqualTo("P-1");
        assertThat(r.direction()).isEqualTo("INCOMING");
        assertThat(r.status()).isEqualTo("VERIFIED");    // a PaymentStatus on a repayment row
        assertThat(r.customerId()).isEqualTo(7L);        // from the payment's loan
        assertThat(r.repaymentDate()).isEqualTo(LocalDate.of(2026, 6, 30)); // the loan's due date
    }

    @Test
    void defaultOrderIsNewestFirstAndPagesAreStable() {
        assertThat(query.page(ALL, FilterSpec.empty(), 0, 100, null, null).stream().map(TransactionView::id).toList())
                .containsExactly("P-4", "P-3", "P-1", "D-3", "D-2");
        assertThat(query.page(ALL, FilterSpec.empty(), 2, 2, null, null).stream().map(TransactionView::id).toList())
                .containsExactly("P-1", "D-3");
    }

    @Test
    void totalsFollowTheWholeFilterAndNotThePage() {
        // June only (inclusive bounds): D-3, P-1, P-3 — page of 1 must not move the totals.
        LedgerFilter june = new LedgerFilter(null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        assertThat(query.page(june, FilterSpec.empty(), 0, 1, null, null)).hasSize(1);
        Totals t = query.totals(june, FilterSpec.empty());
        assertThat(t.count()).isEqualTo(3);
        assertThat(t.inPaise()).isEqualTo(100_000L);
        assertThat(t.pendingInPaise()).isEqualTo(250_000L);
        assertThat(t.outPaise()).isEqualTo(500_000L);

        // Direction and a column filter narrow the totals too.
        assertThat(query.totals(new LedgerFilter(null, "INCOMING", null, null), FilterSpec.empty()).outPaise()).isZero();
        Totals verified = query.totals(ALL, spec("f.status", "text:eq:verified"));
        assertThat(verified.count()).isEqualTo(2);
        assertThat(verified.inPaise()).isEqualTo(140_000L);
    }

    @Test
    void searchMatchesNameMobileAndLoanId() {
        assertThat(ids(new LedgerFilter("aman", null, null, null), FilterSpec.empty())).containsExactlyInAnyOrder("D-2", "P-1", "P-3");
        assertThat(ids(new LedgerFilter("9123", null, null, null), FilterSpec.empty())).containsExactlyInAnyOrder("D-3", "P-4");
        assertThat(ids(new LedgerFilter("3", null, null, null), FilterSpec.empty())).contains("D-3");
        assertThat(ids(new LedgerFilter("zzz", null, null, null), FilterSpec.empty())).isEmpty();
    }

    @Test
    void amountFilterIsInRupeesAndColumnsAreOnlyNarrowing() {
        // ₹1,000 .. ₹2,500 → 100_000..250_000 paise: P-1 and P-3 (the rejected ₹5,000 is out regardless).
        assertThat(ids(ALL, spec("f.amount", "num:1000..2500"))).containsExactlyInAnyOrder("P-1", "P-3");
        // A filter ANDs after the base clauses: INCOMING + type=DISBURSAL is empty, never widened.
        assertThat(ids(new LedgerFilter(null, "INCOMING", null, null), spec("f.type", "in:DISBURSAL"))).isEmpty();
    }

    @Test
    void onlyTheTypeColumnHasAFacetAndItRunsInsideTheBaseClauses() {
        SqlFacets.Facet all = query.facet(ALL, FilterSpec.empty(), "type");
        assertThat(all.values()).containsExactly("DISBURSAL", "REPAYMENT");

        SqlFacets.Facet outgoing = query.facet(new LedgerFilter(null, "OUTGOING", null, null), FilterSpec.empty(), "type");
        assertThat(outgoing.values()).containsExactly("DISBURSAL");

        assertThat(query.facet(ALL, FilterSpec.empty(), "borrower").values()).isEmpty();
        assertThat(query.facet(ALL, FilterSpec.empty(), "status").values()).isEmpty();
    }
}
