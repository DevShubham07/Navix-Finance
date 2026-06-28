package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.collections.domain.DpdBucket;
import com.navix.collections.dto.CollectionsDtos.CaseDetailView;
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

    private CollectionsService service;

    private final UUID caseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CollectionsService(caseRepository, interactionRepository,
                loanDirectory, staffDirectory, new DpdCalculator(), event -> {});
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
                "Asha Verma", "ABXXXXX34F", "Acme Corp", "SALARIED", 3_200_000L, "HDFC");
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
}
