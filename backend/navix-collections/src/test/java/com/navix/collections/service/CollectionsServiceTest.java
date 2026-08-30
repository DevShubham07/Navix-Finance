package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.collections.domain.DpdBucket;
import com.navix.collections.dto.CollectionsDtos.CaseDetailView;
import com.navix.collections.dto.CollectionsDtos.CaseView;
import com.navix.collections.dto.CollectionsDtos.UpcomingLoanView;
import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.InteractionLog;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.InteractionLogRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CollectionsServiceTest {

    private static final String OFFICER_ROLE = "COLLECTION_EXECUTIVE";

    @Mock
    private CollectionCaseRepository caseRepository;
    @Mock
    private InteractionLogRepository interactionRepository;
    @Mock
    private LoanDirectory loanDirectory;
    @Mock
    private StaffDirectory staffDirectory;

    @Mock
    private com.navix.common.loan.ApplicationActorDirectory applicationActorDirectory;

    private CollectionsService service;

    private final UUID caseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CollectionsService(caseRepository, interactionRepository,
                loanDirectory, staffDirectory, new DpdCalculator(), applicationActorDirectory,
                event -> {});
        // Default actor is a Collection Head (allowed to assign); tests override where needed.
        ActorContext.set(new CurrentActor("100", "Head", "COLLECTION_HEAD"));
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private CollectionCase existingCase() {
        CollectionCase c = new CollectionCase();
        c.setId(caseId);
        c.setLoanId(2L);
        return c;
    }

    private LoanSummary loanSummary(long loanId, LocalDate dueDate) {
        return new LoanSummary(loanId, 7L, 1L, "ACTIVE",
                800_000L, 705_600L, 1_040_000L, 1_040_000L,
                LocalDate.now().minusDays(30), dueDate,
                "Asha Verma", "ABXXXXX34F", "Acme Corp", "SALARIED", 3_200_000L, "HDFC",
                dueDate != null && dueDate.isAfter(LocalDate.now()));
    }

    /**
     * The point of the rebuild: the worklist is driven by LOANS, not by hand-opened cases. A borrower
     * past due whom nobody has opened a case for used to appear in no DPD bucket at all — the register
     * showed the bookkeeping instead of the debt.
     */
    @Test
    void worklistIncludesALoanThatHasNoCaseYet() {
        LoanSummary overdue = loanSummary(2L, LocalDate.now().minusDays(9));
        when(loanDirectory.listCollectible(any())).thenReturn(java.util.List.of(overdue));
        when(caseRepository.findByLoanIdIn(java.util.List.of(2L))).thenReturn(java.util.List.of());
        when(applicationActorDirectory.byLoanId(java.util.List.of(2L))).thenReturn(java.util.Map.of());

        var rows = service.worklist(LocalDate.now());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).caseId()).isNull();
        assertThat(rows.get(0).assignedOfficerName()).isNull();
        assertThat(rows.get(0).bucket()).isEqualTo(DpdBucket.T8_T30);
        assertThat(rows.get(0).dpd()).isEqualTo(9);
    }

    /** A loan still running to term is workable, and flagged so the UI never calls it delinquent. */
    @Test
    void worklistFlagsANotYetDueLoanAsPreDueInTheUpcomingBucket() {
        LoanSummary soon = loanSummary(3L, LocalDate.now().plusDays(4));
        when(loanDirectory.listCollectible(any())).thenReturn(java.util.List.of(soon));
        when(caseRepository.findByLoanIdIn(java.util.List.of(3L))).thenReturn(java.util.List.of());
        when(applicationActorDirectory.byLoanId(java.util.List.of(3L))).thenReturn(java.util.Map.of());

        var rows = service.worklist(LocalDate.now());

        assertThat(rows.get(0).preDue()).isTrue();
        assertThat(rows.get(0).bucket()).isEqualTo(DpdBucket.UPCOMING);
        assertThat(rows.get(0).dpd()).isZero();
    }

    /** A settled loan drops off the worklist — there is nothing left to collect. */
    @Test
    void worklistDropsSettledLoans() {
        LoanSummary closed = new LoanSummary(4L, 7L, 1L, "CLOSED", 800_000L, 705_600L, 1_040_000L, 0L,
                LocalDate.now().minusDays(30), LocalDate.now().minusDays(2),
                "Asha Verma", "ABXXXXX34F", "Acme Corp", "SALARIED", 3_200_000L, "HDFC", false);
        when(loanDirectory.listCollectible(any())).thenReturn(java.util.List.of(closed));

        assertThat(service.worklist(LocalDate.now())).isEmpty();
    }

    @Test
    void openCaseReusesExistingCaseForLoanAndMarksInCollections() {
        long loanId = 2L;
        when(loanDirectory.findLoan(loanId)).thenReturn(Optional.of(loanSummary(loanId, LocalDate.now())));
        when(caseRepository.findByLoanId(loanId)).thenReturn(Optional.of(existingCase()));

        CaseDetailView result = service.openCase(loanId);

        assertThat(result.id()).isEqualTo(caseId);
        assertThat(result.loanId()).isEqualTo(loanId);
        assertThat(result.loan()).isNotNull();
        assertThat(result.loan().outstandingPaise()).isEqualTo(1_040_000L);
        verify(loanDirectory).markInCollections(loanId);
    }

    @Test
    void openCaseCreatesNewCaseWhenNoneExists() {
        long loanId = 2L;
        when(loanDirectory.findLoan(loanId)).thenReturn(Optional.of(loanSummary(loanId, LocalDate.now())));
        when(caseRepository.findByLoanId(loanId)).thenReturn(Optional.empty());
        when(caseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CaseDetailView result = service.openCase(loanId);

        assertThat(result.loanId()).isEqualTo(loanId);
        verify(loanDirectory).markInCollections(loanId);
    }

    @Test
    void openCaseRejectsAbsentLoan() {
        when(loanDirectory.findLoan(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openCase(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void caseDetailComputesLiveDpdFromDueDate() {
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(existingCase()));
        when(loanDirectory.findLoan(2L)).thenReturn(Optional.of(loanSummary(2L, LocalDate.now().minusDays(10))));

        CaseDetailView detail = service.getCaseDetail(caseId);

        assertThat(detail.dpd()).isEqualTo(10);
        assertThat(detail.bucket()).isEqualTo(DpdBucket.T8_T30);
    }

    @Test
    void logInteractionPaidWithoutProofIsRejected() {
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(existingCase()));

        assertThatThrownBy(() ->
                service.logInteraction(caseId, "CALL", "PAID", null, "  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("proof");
    }

    @Test
    void logInteractionPaidWithProofSucceeds() {
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(existingCase()));
        when(interactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        InteractionLog log = service.logInteraction(caseId, "CALL", "PAID", null, "TXN-12345");

        assertThat(log.getCollectionCaseId()).isEqualTo(caseId);
        assertThat(log.getOutcome()).isEqualTo("PAID");
        assertThat(log.getProofRef()).isEqualTo("TXN-12345");
        assertThat(log.getLoggedAt()).isNotNull();
    }

    @Test
    void logInteractionNonPaidOutcomeNeedsNoProof() {
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(existingCase()));
        when(interactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        InteractionLog log = service.logInteraction(caseId, "CALL", "NO_ANSWER", null, null);

        assertThat(log.getOutcome()).isEqualTo("NO_ANSWER");
        assertThat(log.getProofRef()).isNull();
    }

    @Test
    void assignOfficerSetsActiveExecutiveOnCase() {
        long officerId = 9L;
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(existingCase()));
        when(staffDirectory.isActiveWithRole(officerId, OFFICER_ROLE)).thenReturn(true);
        when(caseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CollectionCase result = service.assignOfficer(caseId, officerId);

        assertThat(result.getAssignedOfficerId()).isEqualTo(officerId);
    }

    @Test
    void assignOfficerRejectsNonActiveExecutive() {
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(existingCase()));
        when(staffDirectory.isActiveWithRole(5L, OFFICER_ROLE)).thenReturn(false);

        assertThatThrownBy(() -> service.assignOfficer(caseId, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("executive");
    }

    @Test
    void assignOfficerRejectsNonHeadActor() {
        // A Collection Executive must not be able to assign cases (collections management is head-only).
        ActorContext.set(new CurrentActor("9", "Sana", "COLLECTION_EXECUTIVE"));

        assertThatThrownBy(() -> service.assignOfficer(caseId, 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Collection Head");
    }

    @Test
    void caseDetailByLoanIdFindsTheCaseForThatLoan() {
        when(caseRepository.findFirstByLoanIdOrderByCreatedAtDesc(2L))
                .thenReturn(Optional.of(existingCase()));
        when(loanDirectory.findLoan(2L)).thenReturn(Optional.of(loanSummary(2L, LocalDate.now().minusDays(10))));

        CaseDetailView detail = service.getCaseDetailByLoanId(2L);

        assertThat(detail.id()).isEqualTo(caseId);
        assertThat(detail.loanId()).isEqualTo(2L);
        assertThat(detail.dpd()).isEqualTo(10);
    }

    @Test
    void caseDetailByLoanIdIsNotFoundForALoanWithNoCase() {
        // The normal state for a healthy loan — no collection case has ever been opened for it.
        when(caseRepository.findFirstByLoanIdOrderByCreatedAtDesc(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCaseDetailByLoanId(404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void caseDetailByLoanIdRejectsBorrower() {
        // Unlike getCaseDetail(UUID) (keyed by an unguessable UUID), this is keyed by a sequential
        // loan id, so it must not be open to a borrower — that would let any authenticated borrower
        // walk loan ids upward and harvest other borrowers' collections data.
        ActorContext.set(new CurrentActor("55", "A Borrower", "BORROWER"));

        assertThatThrownBy(() -> service.getCaseDetailByLoanId(2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Staff role required");
    }

    @Test
    void caseDetailByLoanIdRejectsDsa() {
        // A DSA is staff but is firewalled from all customer data, including collections activity.
        ActorContext.set(new CurrentActor("77", "An Agent", "DSA"));

        assertThatThrownBy(() -> service.getCaseDetailByLoanId(2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DSAs cannot view collections cases");
    }

    @Test
    void caseDetailByLoanIdAllowsALegitimateStaffRole() {
        // Default actor from setUp() is COLLECTION_HEAD; assert the happy path explicitly here too
        // so the guard's positive case is co-located with its two rejection cases above.
        when(caseRepository.findFirstByLoanIdOrderByCreatedAtDesc(2L))
                .thenReturn(Optional.of(existingCase()));
        when(loanDirectory.findLoan(2L)).thenReturn(Optional.of(loanSummary(2L, LocalDate.now())));

        CaseDetailView detail = service.getCaseDetailByLoanId(2L);

        assertThat(detail.loanId()).isEqualTo(2L);
    }

    // --- listCaseViews (the batched list-view path) -----------------------------------------

    @Test
    void listCaseViewsResolvesLoansAndOfficerNamesInOneBatchedCallEach() {
        CollectionCase assigned = existingCase(); // loanId 2, no officer yet — exercises the null id
        CollectionCase withOfficer = new CollectionCase();
        withOfficer.setId(UUID.randomUUID());
        withOfficer.setLoanId(3L);
        withOfficer.setAssignedOfficerId(9L);
        when(caseRepository.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(assigned, withOfficer));
        when(loanDirectory.findLoans(any())).thenReturn(java.util.Map.of(
                2L, loanSummary(2L, LocalDate.now().minusDays(5)),
                3L, loanSummary(3L, LocalDate.now().minusDays(2))));
        when(staffDirectory.namesFor(any())).thenReturn(java.util.Map.of(9L, "Sana Khan"));

        List<CaseView> views = service.listCaseViews();

        assertThat(views).hasSize(2);
        assertThat(views).filteredOn(v -> v.loanId() == 2L).singleElement()
                .satisfies(v -> assertThat(v.assignedOfficerName()).isNull());
        assertThat(views).filteredOn(v -> v.loanId() == 3L).singleElement()
                .satisfies(v -> assertThat(v.assignedOfficerName()).isEqualTo("Sana Khan"));
        verify(loanDirectory, times(1)).findLoans(any());
        verify(staffDirectory, times(1)).namesFor(any());
        verify(loanDirectory, never()).findLoan(anyLong());
        verify(staffDirectory, never()).findStaff(anyLong());
    }

    @Test
    void listCaseViewsDropsSettledLoans() {
        CollectionCase c = existingCase();
        LoanSummary closed = new LoanSummary(2L, 7L, 1L, "CLOSED", 800_000L, 705_600L, 1_040_000L, 0L,
                LocalDate.now().minusDays(30), LocalDate.now().minusDays(2),
                "Asha Verma", "ABXXXXX34F", "Acme Corp", "SALARIED", 3_200_000L, "HDFC", false);
        when(caseRepository.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(c));
        when(loanDirectory.findLoans(any())).thenReturn(java.util.Map.of(2L, closed));
        when(staffDirectory.namesFor(any())).thenReturn(java.util.Map.of());

        assertThat(service.listCaseViews()).isEmpty();
    }

    // --- upcomingWatchlist (the pre-due watchlist behind the UPCOMING bucket) -------------------

    @Test
    void upcomingWatchlistDropsLoansThatAlreadyHaveACase() {
        // Loan 2 has a case (it surfaces through listCaseViews with a real case id); loan 3 does not.
        // Listing both would double loan 2 inside a single bucket.
        when(loanDirectory.listUpcoming(any())).thenReturn(List.of(
                loanSummary(2L, LocalDate.now().plusDays(5)),
                loanSummary(3L, LocalDate.now().plusDays(9))));
        when(caseRepository.findByLoanIdIn(List.of(2L, 3L))).thenReturn(List.of(existingCase()));

        List<UpcomingLoanView> rows = service.upcomingWatchlist(LocalDate.now());

        assertThat(rows).extracting(UpcomingLoanView::loanId).containsExactly(3L);
    }

    @Test
    void upcomingWatchlistComputesDaysToDueFromTheAsOfDate() {
        LocalDate on = LocalDate.of(2026, 8, 26);
        when(loanDirectory.listUpcoming(on)).thenReturn(List.of(loanSummary(3L, LocalDate.of(2026, 9, 2))));
        when(caseRepository.findByLoanIdIn(List.of(3L))).thenReturn(List.of());

        List<UpcomingLoanView> rows = service.upcomingWatchlist(on);

        assertThat(rows).singleElement()
                .satisfies(r -> {
                    assertThat(r.daysToDue()).isEqualTo(7);
                    assertThat(r.borrowerName()).isEqualTo("Asha Verma");
                    assertThat(r.customerId()).isEqualTo(7L);
                });
    }

    @Test
    void upcomingWatchlistShortCircuitsWithoutQueryingCasesWhenThereAreNoLoans() {
        // findByLoanIdIn against an empty collection is not valid SQL, so it must never be reached.
        when(loanDirectory.listUpcoming(any())).thenReturn(List.of());

        assertThat(service.upcomingWatchlist(LocalDate.now())).isEmpty();
        verify(caseRepository, never()).findByLoanIdIn(any());
    }

    @Test
    void upcomingWatchlistRejectsABorrower() {
        ActorContext.set(new CurrentActor("7", "A Borrower", "BORROWER"));

        assertThatThrownBy(() -> service.upcomingWatchlist(LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Staff role required");
    }

    @Test
    void upcomingWatchlistRejectsDsa() {
        // A DSA is staff but is firewalled from all borrower-identifying data.
        ActorContext.set(new CurrentActor("77", "An Agent", "DSA"));

        assertThatThrownBy(() -> service.upcomingWatchlist(LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DSAs cannot view collections cases");
    }
}
