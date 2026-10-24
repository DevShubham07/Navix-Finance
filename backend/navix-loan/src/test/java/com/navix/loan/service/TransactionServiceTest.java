package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.dto.LoanDtos.TransactionView;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private LoanRepository loanRepository;
    @Mock
    private TransactionLedgerQuery ledgerQuery;
    @Mock
    private LoanApplicationRepository applicationRepository;
    @Mock
    private CustomerProfileRepository profileRepository;
    @Mock
    private DocumentStoragePort storage;

    private TransactionService service;

    @BeforeEach
    void setUp() {
        service = new TransactionService(loanRepository, ledgerQuery, applicationRepository, profileRepository, storage);
        // Only the proof-resolution test cares about the return value; a harmless default elsewhere.
        lenient().when(storage.presignDownload("loan/repayment-proof/1.jpg")).thenReturn("https://s3.example/signed?p=1");
    }

    private Loan loan2() {
        Loan loan = new Loan();
        loan.setId(2L);
        loan.setCustomerId(7L);
        return loan;
    }

    private LoanApplication app5() {
        LoanApplication a = new LoanApplication();
        a.setId(5L);
        a.setLoanId(2L);
        a.setCustomerId(7L);
        return a;
    }

    private CustomerProfile profile5() {
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(5L);
        p.setFullName("Aman");
        return p;
    }

    private static TransactionView row(String id, String proofKey) {
        return new TransactionView(id, "REPAYMENT", "INCOMING", 2L, 7L, "Aman", "ABCDE1234F", 100_000L,
                "PAY-IN-1", "VERIFIED", LocalDate.of(2026, 6, 20), proofKey, null, 1);
    }

    @Test
    void borrowersByLoanIdUsesLoanCustomerIdAndProfileName() {
        when(loanRepository.findAllById(List.of(2L))).thenReturn(List.of(loan2()));
        when(applicationRepository.findByLoanIdIn(List.of(2L))).thenReturn(List.of(app5()));
        when(profileRepository.findByApplicationIdIn(List.of(5L))).thenReturn(List.of(profile5()));

        Map<Long, TransactionService.BorrowerRef> refs = service.borrowersByLoanId(List.of(2L));

        assertThat(refs).hasSize(1);
        TransactionService.BorrowerRef ref = refs.get(2L);
        assertThat(ref.customerId()).isEqualTo(7L); // from the loan
        assertThat(ref.borrowerName()).isEqualTo("Aman"); // from the profile
    }

    @Test
    void profilesByLoanIdKeepsTheLastApplicationWithAProfile() {
        LoanApplication appNoProfile = new LoanApplication();
        appNoProfile.setId(10L);
        appNoProfile.setLoanId(2L);
        appNoProfile.setCustomerId(7L);

        LoanApplication appWithProfile = new LoanApplication();
        appWithProfile.setId(11L);
        appWithProfile.setLoanId(2L);
        appWithProfile.setCustomerId(7L);

        CustomerProfile profile = new CustomerProfile();
        profile.setApplicationId(11L);
        profile.setFullName("Rita");

        when(applicationRepository.findByLoanIdIn(List.of(2L))).thenReturn(List.of(appNoProfile, appWithProfile));
        when(profileRepository.findByApplicationIdIn(List.of(10L, 11L))).thenReturn(List.of(profile));

        Map<Long, CustomerProfile> result = service.profilesByLoanId(List.of(2L));

        assertThat(result).hasSize(1);
        assertThat(result.get(2L).getFullName()).isEqualTo("Rita");
    }

    // ------------------------------------------------------------------ paging
    //
    // The ledger is one native query now (TransactionLedgerQuery); these pin what the SERVICE owns:
    // the page clamp, the totals coming from the aggregate (not the page), presigning only the page,
    // and the column filters reaching the query. The SQL itself is covered against a real Postgres in
    // TransactionLedgerQueryIntegrationTest.

    @Test
    void totalsAndCountComeFromTheAggregateNotFromThePage() {
        when(ledgerQuery.totals(any(), any())).thenReturn(new TransactionLedgerQuery.Totals(60, 500_000L, 200_000L, 250_000L));
        when(ledgerQuery.page(any(), any(), eq(0), eq(1), any(), any())).thenReturn(List.of(row("P-1", null)));

        var first = service.listTransactions(null, null, null, null, 1, 1);

        assertThat(first.rows()).hasSize(1);
        assertThat(first.total()).isEqualTo(60);
        assertThat(first.totalInPaise()).isEqualTo(500_000L);
        assertThat(first.totalOutPaise()).isEqualTo(200_000L);
        assertThat(first.pendingInPaise()).isEqualTo(250_000L);
    }

    @Test
    void presignsOnlyThePagesProofs() {
        when(ledgerQuery.totals(any(), any())).thenReturn(new TransactionLedgerQuery.Totals(2, 0, 0, 0));
        when(ledgerQuery.page(any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(List.of(row("P-1", "loan/repayment-proof/1.jpg"), row("P-2", null)));

        var page = service.listTransactions(null, "INCOMING", null, null, 1, 25);

        assertThat(page.rows().get(0).proofUrl()).isEqualTo("https://s3.example/signed?p=1");
        assertThat(page.rows().get(1).proofUrl()).isNull();
        verify(storage, times(1)).presignDownload(any());
    }

    @Test
    void clampsPageAndSize() {
        when(ledgerQuery.totals(any(), any())).thenReturn(new TransactionLedgerQuery.Totals(0, 0, 0, 0));
        when(ledgerQuery.page(any(), any(), anyInt(), anyInt(), any(), any())).thenReturn(List.of());

        var page = service.listTransactions(null, null, null, null, 0, 5_000);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(TransactionService.MAX_PAGE_SIZE);
        verify(ledgerQuery).page(any(), any(), eq(0), eq(TransactionService.MAX_PAGE_SIZE), any(), any());
    }

    @Test
    void passesThePeriodDirectionSearchAndColumnFiltersToTheQuery() {
        when(ledgerQuery.totals(any(), any())).thenReturn(new TransactionLedgerQuery.Totals(0, 0, 0, 0));
        when(ledgerQuery.page(any(), any(), anyInt(), anyInt(), any(), any())).thenReturn(List.of());
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);

        service.page("  Aman ", "incoming", from, to, 2, 10, "amount", "asc",
                Map.of("f.status", "text:eq:VERIFIED", "f.bogus", "text:eq:x"));

        var filter = org.mockito.ArgumentCaptor.forClass(TransactionLedgerQuery.LedgerFilter.class);
        var spec = org.mockito.ArgumentCaptor.forClass(com.navix.common.query.FilterSpec.class);
        verify(ledgerQuery).page(filter.capture(), spec.capture(), eq(10), eq(10), eq("amount"), eq("asc"));
        assertThat(filter.getValue()).isEqualTo(new TransactionLedgerQuery.LedgerFilter("aman", "INCOMING", from, to));
        // A column the ledger did not register is dropped, never an error.
        assertThat(spec.getValue().filters()).containsOnlyKeys("status");
    }

    @Test
    void facetDropsItsOwnFilter() {
        when(ledgerQuery.facet(any(), any(), eq("type"))).thenReturn(new com.navix.common.query.SqlFacets.Facet(List.of(), false));

        service.facets(null, null, null, null, "type", Map.of("f.type", "in:REPAYMENT", "f.status", "text:eq:VERIFIED"));

        var spec = org.mockito.ArgumentCaptor.forClass(com.navix.common.query.FilterSpec.class);
        verify(ledgerQuery).facet(any(), spec.capture(), eq("type"));
        assertThat(spec.getValue().filters()).containsOnlyKeys("status");
    }
}
