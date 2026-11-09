package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.collections.service.CollectionsWorklistQuery.WorklistFilter;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.InteractionLogRepository;
import com.navix.common.loan.ApplicationActorDirectory;
import com.navix.common.loan.ApplicationActorDirectory.HandledBy;
import com.navix.common.loan.CustomerTrustDirectory;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.common.query.FilterSpec;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link CollectionsService#worklistPage}: the stored path pages in SQL and enriches only the page;
 * the derived path (outstanding, the two handled-by names) filters/sorts in Java over the ids SQL
 * returned. The SQL itself is mocked here — see the integration test for real rows.
 */
@ExtendWith(MockitoExtension.class)
class CollectionsWorklistPageTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Mock CollectionCaseRepository caseRepository;
    @Mock InteractionLogRepository interactionRepository;
    @Mock LoanDirectory loanDirectory;
    @Mock StaffDirectory staffDirectory;
    @Mock ApplicationActorDirectory applicationActorDirectory;
    @Mock CustomerTrustDirectory customerTrustDirectory;
    @Mock CollectionsWorklistQuery query;

    private CollectionsService service;

    @BeforeEach
    void setUp() {
        service = new CollectionsService(caseRepository, interactionRepository, loanDirectory, staffDirectory,
                new DpdCalculator(), applicationActorDirectory, customerTrustDirectory, e -> {}, query);
        ActorContext.set(new CurrentActor("100", "Head", "COLLECTION_HEAD"));
        lenient().when(caseRepository.findByLoanIdIn(any())).thenReturn(List.of());
        lenient().when(applicationActorDirectory.byLoanId(any())).thenReturn(Map.of());
        lenient().when(customerTrustDirectory.forCustomers(any())).thenReturn(Map.of());
        lenient().when(staffDirectory.namesFor(any())).thenReturn(Map.of());
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private static LoanSummary loan(long id, long outstandingPaise) {
        LocalDate due = LocalDate.now(IST).minusDays(3);
        return new LoanSummary(id, 50 + id, 500 + id, "OVERDUE", 800_000L, 705_600L, 1_040_000L, outstandingPaise,
                due.minusDays(20), due, "Borrower " + id, "ABCDE1234F", "90000000" + id, "Acme", "SALARIED",
                3_200_000L, "HDFC", false);
    }

    @Test
    void aStoredOnlyViewPagesInSqlAndEnrichesOnlyThePage() {
        when(query.count(any(), any())).thenReturn(300L);
        when(query.pageIds(any(), any(), eq(50), eq(25), eq("dpd"), eq("desc"))).thenReturn(List.of(11L, 12L));
        when(loanDirectory.findLoans(List.of(11L, 12L))).thenReturn(Map.of(11L, loan(11, 1), 12L, loan(12, 2)));

        var page = service.worklistPage(null, null, null, null, null, 3, 25, "dpd", "desc", Map.of("f.dpd", "num:1.."));

        assertThat(page.total()).isEqualTo(300);
        assertThat(page.rows()).extracting(r -> r.loanId()).containsExactly(11L, 12L);
        // The whole book is never read: no unpaged id list, no whole-worklist read.
        verify(query, never()).allIds(any(), any(), any(), any());
        verify(loanDirectory, never()).listCollectible(any());
    }

    @Test
    void anOutstandingFilterAndSortRunInJavaOverTheSqlIdsAndPageTheSurvivors() {
        // SQL narrowed to 5 loans; outstanding is DERIVED so it is judged here, in rupees on the wire.
        List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L);
        when(query.allIds(any(), any(), any(), any())).thenReturn(ids);
        when(loanDirectory.findLoans(ids)).thenReturn(Map.of(
                1L, loan(1, 100_000), 2L, loan(2, 500_000), 3L, loan(3, 250_000),
                4L, loan(4, 900_000), 5L, loan(5, 50_000)));
        when(loanDirectory.findLoans(List.of(3L, 2L))).thenReturn(Map.of(2L, loan(2, 500_000), 3L, loan(3, 250_000)));

        // >= Rs 2,000 and <= Rs 6,000 keeps 2 (5,000) and 3 (2,500); sorted ascending by balance -> 3, 2.
        var page = service.worklistPage(null, null, null, null, null, 1, 25, "outstandingPaise", "asc",
                Map.of("f.outstandingPaise", "num:2000..6000"));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.rows()).extracting(r -> r.loanId()).containsExactly(3L, 2L);
        // The unfiltered SQL count is not used for a derived view: the survivors define the total.
        verify(query, never()).count(any(), any());
    }

    @Test
    void aHandledByFilterTreatsNoRecordedActorAsBlank() {
        List<Long> ids = List.of(1L, 2L, 3L);
        when(query.allIds(any(), any(), any(), any())).thenReturn(ids);
        when(applicationActorDirectory.byLoanId(ids)).thenReturn(Map.of(
                1L, new HandledBy(9L, "Meera", null, null),
                2L, new HandledBy(8L, "Ravi", null, null)));
        when(loanDirectory.findLoans(List.of(1L, 3L))).thenReturn(Map.of(1L, loan(1, 1), 3L, loan(3, 3)));

        var page = service.worklistPage(null, null, null, null, null, 1, 25, "dpd", "desc",
                Map.of("f.creditDecidedByName", "in:Meera,__NULL__"));

        assertThat(page.rows()).extracting(r -> r.loanId()).containsExactly(1L, 3L);
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void aScopedExecutivesRequestCarriesTheirScopeIntoTheQuery() {
        ActorContext.set(new CurrentActor("7", "Exec", "COLLECTION_EXECUTIVE"));
        when(query.count(any(), any())).thenReturn(0L);
        when(query.pageIds(any(), any(), eq(0), eq(25), any(), any())).thenReturn(List.of());

        service.worklistPage(null, null, null, null, null, 1, 25, "dpd", "desc", Map.of());

        ArgumentCaptor<WorklistFilter> f = ArgumentCaptor.forClass(WorklistFilter.class);
        verify(query).count(f.capture(), any());
        assertThat(f.getValue().scoped()).isTrue();
        assertThat(f.getValue().officerId()).isEqualTo(7L);
    }

    @Test
    void anExecutivesHandledByFacetOnlyListsNamesFromTheirOwnScopedIds() {
        ActorContext.set(new CurrentActor("7", "Exec", "COLLECTION_EXECUTIVE"));
        // The query is where scope lives: it returns only this officer's ids, and the facet reads names
        // off exactly those — a name that belongs to someone else's loan can never be offered.
        when(query.allIds(any(), any(), any(), any())).thenReturn(List.of(1L));
        when(applicationActorDirectory.byLoanId(List.of(1L))).thenReturn(Map.of(
                1L, new HandledBy(9L, "Meera", null, "Dev")));

        var facet = service.worklistFacet("creditDecidedByName", null, null, null, null, null, Map.of());

        assertThat(facet.values()).containsExactly("Meera");
        ArgumentCaptor<WorklistFilter> f = ArgumentCaptor.forClass(WorklistFilter.class);
        verify(query).allIds(f.capture(), any(FilterSpec.class), any(), any());
        assertThat(f.getValue().scoped()).isTrue();
        assertThat(f.getValue().officerId()).isEqualTo(7L);
    }

    @Test
    void aFacetOnAnIdentityColumnOffersNothingAndReadsNothing() {
        var facet = service.worklistFacet("mobile", null, null, null, null, null, Map.of());

        assertThat(facet.values()).isEmpty();
        verify(query, never()).allIds(any(), any(), any(), any());
    }

    @Test
    void aNonStaffCallerIsRefused() {
        ActorContext.set(new CurrentActor("1", "Dsa", "DSA"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.worklistPage(null, null, null, null, null, 1, 25, null, null, Map.of()))
                .hasMessageContaining("DSAs");
    }

    @Test
    void theBucketSummaryCountsFromSqlAndSumsTheDerivedBalanceIgnoringEveryFilter() {
        java.util.Map<Long, String> buckets = new java.util.LinkedHashMap<>();
        buckets.put(1L, "T0_T7");
        buckets.put(2L, "T0_T7");
        buckets.put(3L, "T90_PLUS");
        when(query.bucketsInScope(any())).thenReturn(buckets);
        when(loanDirectory.findLoans(buckets.keySet())).thenReturn(Map.of(
                1L, loan(1, 100_000), 2L, loan(2, 250_000), 3L, loan(3, 40_000)));

        var rows = service.worklistSummary(null);

        assertThat(rows).hasSize(6); // one row per bucket, empty ones included
        var t0 = rows.stream().filter(r -> r.bucket().equals("T0_T7")).findFirst().orElseThrow();
        assertThat(t0.n()).isEqualTo(2);
        assertThat(t0.outstandingPaise()).isEqualTo(350_000L);
        assertThat(rows.stream().filter(r -> r.bucket().equals("T60_T90")).findFirst().orElseThrow().n()).isZero();
        ArgumentCaptor<WorklistFilter> f = ArgumentCaptor.forClass(WorklistFilter.class);
        verify(query).bucketsInScope(f.capture());
        assertThat(f.getValue().needle()).isNull();
        assertThat(f.getValue().bucket()).isNull();
    }
}
