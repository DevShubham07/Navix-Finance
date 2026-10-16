package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.query.ColumnFilter;
import com.navix.common.query.FilterSpec;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.dto.LoanRegisterDtos.LoanRegisterRow;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.service.LoanRegisterQuery.Hit;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The paged register's two shapes, with SQL stubbed out (the SQL itself is run by
 * {@code LoanRegisterPageIntegrationTest}): the page path must enrich ONLY the page's ids, and the two
 * Java-side columns (outstanding, collections officer) must filter and sort over the SQL result
 * without ever reaching SQL.
 */
@ExtendWith(MockitoExtension.class)
class LoanRegisterPageTest {

    @Mock private LoanRepository loanRepository;
    @Mock private LoanApplicationRepository applicationRepository;
    @Mock private CustomerProfileRepository profileRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private CustomerTrustSignalsService trustSignals;
    @Mock private LoanRegisterQuery registerQuery;
    @Mock private com.navix.common.collections.SettlementDirectory settlementDirectory;
    @Mock private com.navix.common.collections.CollectionCaseDirectory collectionCaseDirectory;
    @Mock private com.navix.common.staff.StaffDirectory staffDirectory;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private ApplicationFlowService applicationFlowService;
    @Mock private DsaCommissionService dsaCommissionService;
    @Mock private com.navix.common.storage.DocumentStoragePort storage;

    private RepaymentService repaymentService;
    private LoanRegisterService service;

    @BeforeEach
    void setUp() {
        LoanMath loanMath = new LoanMath();
        repaymentService = new RepaymentService(paymentRepository, loanRepository, loanMath,
                applicationFlowService, settlementDirectory, eventPublisher, dsaCommissionService, storage);
        service = new LoanRegisterService(loanRepository, applicationRepository, profileRepository,
                repaymentService, loanMath, collectionCaseDirectory, staffDirectory, trustSignals, registerQuery);
        lenient().when(settlementDirectory.approvedSettlementAmounts(any())).thenReturn(Map.of());
        lenient().when(paymentRepository.sumAmountByLoanIdInAndStatus(anyList(), any())).thenReturn(List.of());
        lenient().when(collectionCaseDirectory.assignedOfficerByLoanId(anyCollection())).thenReturn(Map.of());
        lenient().when(applicationRepository.findByLoanIdIn(anyList())).thenReturn(List.of());
        lenient().when(staffDirectory.findStaff(anyLong())).thenReturn(Optional.empty());
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private static Loan loan(long id, long principal) {
        Loan l = new Loan();
        l.setId(id);
        l.setCustomerId(100 + id);
        l.setPrincipal(principal);
        l.setNetDisbursed(principal);
        l.setTotalRepayable(principal);
        l.setDisbursedOn(LocalDate.now().minusDays(2));
        l.setDueDate(LocalDate.now().plusDays(20));
        l.setStatus(LoanStatus.ACTIVE);
        return l;
    }

    private void stubLoans(Loan... loans) {
        when(loanRepository.findAllById(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return List.of(loans).stream().filter(l -> ids.contains(l.getId())).toList();
        });
    }

    private static List<Long> ids(List<LoanRegisterRow> rows) {
        return rows.stream().map(LoanRegisterRow::loanId).toList();
    }

    @Test
    void thePagePathEnrichesOnlyThePagesIds() {
        stubLoans(loan(7, 1_000_000), loan(3, 1_000_000));
        when(registerQuery.count(any(), any())).thenReturn(500L);
        when(registerQuery.pageHits(any(), any(), any(), any(), eq(25), eq(25)))
                .thenReturn(List.of(new Hit(7, 2), new Hit(3, 1)));

        LoanRegisterService.Page page = service.page(null, null, null, null, Map.of(), null, null, 2, 25);

        assertThat(page.total()).isEqualTo(500);
        assertThat(ids(page.rows())).containsExactly(7L, 3L);
        // The cycle is the one SQL computed over the whole window, not recomputed from two loans.
        assertThat(page.rows()).extracting(LoanRegisterRow::loanCycle).containsExactly(2, 1);
        verify(applicationRepository).findByLoanIdIn(List.of(7L, 3L));
        verify(paymentRepository).sumAmountByLoanIdInAndStatus(eq(List.of(7L, 3L)), any());
        verify(loanRepository, never()).findAllForRegister(any(), any());
        verify(registerQuery, never()).allHits(any(), any(), any(), any());
    }

    @Test
    void storedFiltersGoToSqlAndTheTwoJavaColumnsNeverDo() {
        when(registerQuery.count(any(), any())).thenReturn(0L);
        when(registerQuery.pageHits(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        service.page(null, null, null, null,
                Map.of("f.status", "in:OVERDUE", "f.principalPaise", "num:5000..", "f.dpd", "num:1.."),
                "dpd", "desc", 1, 25);

        ArgumentCaptor<FilterSpec> spec = ArgumentCaptor.forClass(FilterSpec.class);
        verify(registerQuery).count(any(), spec.capture());
        assertThat(spec.getValue().filters().keySet()).containsExactly("dpd", "principalPaise", "status");
    }

    @Test
    void anOutstandingFilterIsEvaluatedInJavaOverEverySqlMatchAndTheTotalIsTheSurvivors() {
        Loan small = loan(1, 500_000);
        Loan mid = loan(2, 1_000_000);
        Loan big = loan(3, 2_000_000);
        stubLoans(small, mid, big);
        when(registerQuery.allHits(any(), any(), any(), any()))
                .thenReturn(List.of(new Hit(1, 1), new Hit(2, 1), new Hit(3, 1)));
        long midBalance = repaymentService.outstandingForAll(List.of(mid), LocalDate.now()).get(2L);

        // The bound is typed in rupees; the balance is paise — scaled on the operand, as SqlFilter does.
        LoanRegisterService.Page page = service.page(null, null, null, null,
                Map.of("f.outstandingPaise", "num:" + (midBalance / 100) + ".."), null, null, 1, 25);

        assertThat(ids(page.rows())).containsExactly(2L, 3L);
        assertThat(page.total()).isEqualTo(2);
        ArgumentCaptor<FilterSpec> sql = ArgumentCaptor.forClass(FilterSpec.class);
        verify(registerQuery).allHits(any(), sql.capture(), any(), any());
        assertThat(sql.getValue().isEmpty()).as("outstanding must never reach SQL").isTrue();
        verify(registerQuery, never()).count(any(), any());
    }

    @Test
    void sortingByOutstandingIsJavaSideAndPagesTheSortedList() {
        stubLoans(loan(1, 500_000), loan(2, 1_000_000), loan(3, 2_000_000));
        when(registerQuery.allHits(any(), any(), any(), any()))
                .thenReturn(List.of(new Hit(1, 1), new Hit(2, 1), new Hit(3, 1)));

        LoanRegisterService.Page first = service.page(null, null, null, null, Map.of(), "outstandingPaise", "desc", 1, 2);
        LoanRegisterService.Page second = service.page(null, null, null, null, Map.of(), "outstandingPaise", "desc", 2, 2);

        assertThat(ids(first.rows())).containsExactly(3L, 2L);
        assertThat(ids(second.rows())).containsExactly(1L);
        assertThat(first.total()).isEqualTo(3);
        // Never asked SQL to order by a column it does not have.
        ArgumentCaptor<String> sort = ArgumentCaptor.forClass(String.class);
        verify(registerQuery, org.mockito.Mockito.times(2)).allHits(any(), any(), sort.capture(), any());
        assertThat(sort.getAllValues()).containsOnlyNulls();
    }

    @Test
    void theOfficerFilterMatchesByNameAndTheBlankSentinelMeansUnallocated() {
        stubLoans(loan(1, 1_000_000), loan(2, 1_000_000), loan(3, 1_000_000));
        when(registerQuery.allHits(any(), any(), any(), any()))
                .thenReturn(List.of(new Hit(1, 1), new Hit(2, 1), new Hit(3, 1)));
        when(collectionCaseDirectory.assignedOfficerByLoanId(anyCollection())).thenReturn(Map.of(1L, 7L, 2L, 8L));
        when(staffDirectory.findStaff(7L)).thenReturn(Optional.of(new StaffSummary(7L, "Meena", "COLLECTION_EXECUTIVE", true)));
        when(staffDirectory.findStaff(8L)).thenReturn(Optional.of(new StaffSummary(8L, "Raj", "COLLECTION_EXECUTIVE", true)));

        assertThat(ids(service.page(null, null, null, null, Map.of("f.assignedOfficerName", "in:Meena"),
                null, null, 1, 25).rows())).containsExactly(1L);
        assertThat(ids(service.page(null, null, null, null, Map.of("f.assignedOfficerName", "in:__NULL__"),
                null, null, 1, 25).rows())).containsExactly(3L);
        assertThat(ids(service.page(null, null, null, null, Map.of("f.assignedOfficerName", "in:Raj,__NULL__"),
                null, null, 1, 25).rows())).containsExactly(2L, 3L);
        // Officer sort: names A-Z, nobody last in both directions.
        assertThat(ids(service.page(null, null, null, null, Map.of(), "assignedOfficerName", "asc", 1, 25).rows()))
                .containsExactly(1L, 2L, 3L);
        assertThat(ids(service.page(null, null, null, null, Map.of(), "assignedOfficerName", "desc", 1, 25).rows()))
                .containsExactly(2L, 1L, 3L);
    }

    @Test
    void theOfficerFacetListsNamesAndBlankWhenSomeLoanHasNoOfficer() {
        when(registerQuery.allHits(any(), any(), any(), any())).thenReturn(List.of(new Hit(1, 1), new Hit(2, 1)));
        when(collectionCaseDirectory.assignedOfficerByLoanId(anyCollection())).thenReturn(Map.of(1L, 7L));
        when(staffDirectory.findStaff(7L)).thenReturn(Optional.of(new StaffSummary(7L, "Meena", "COLLECTION_EXECUTIVE", true)));

        var facet = service.facet("assignedOfficerName", null, null, null, null, Map.of());

        assertThat(facet.values()).containsExactly("Meena", ColumnFilter.BLANK);
        assertThat(facet.truncated()).isFalse();
    }

    @Test
    void aFilterCanOnlyNarrowSoTheRoleGateRunsFirstAndNothingTouchesTheQuery() {
        for (String role : List.of("CREDIT_EXECUTIVE", "BORROWER", "DSA")) {
            ActorContext.set(new CurrentActor("3", "x", role));
            assertThatThrownBy(() -> service.page(null, null, null, null, Map.of("f.status", "in:ACTIVE"),
                    null, null, 1, 25)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.facet("status", null, null, null, null, Map.of()))
                    .isInstanceOf(BusinessException.class);
        }
        verify(registerQuery, never()).count(any(), any());
        verify(registerQuery, never()).facet(any(), any(), any());
    }

    @Test
    void aCollectionHeadMayPage() {
        ActorContext.set(new CurrentActor("2", "Head", "COLLECTION_HEAD"));
        when(registerQuery.count(any(), any())).thenReturn(0L);
        when(registerQuery.pageHits(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        assertThat(service.page(null, null, null, null, Map.of(), null, null, 1, 25).rows()).isEmpty();
    }

    @Test
    void theSizeIsCappedAndAPageBelowOneIsPageOne() {
        when(registerQuery.count(any(), any())).thenReturn(0L);
        when(registerQuery.pageHits(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        service.page(null, null, null, null, Map.of(), null, null, -4, 100_000);

        verify(registerQuery).pageHits(any(), any(), any(), any(), eq(0), eq(LoanRegisterService.MAX_PAGE_SIZE));
    }
}
