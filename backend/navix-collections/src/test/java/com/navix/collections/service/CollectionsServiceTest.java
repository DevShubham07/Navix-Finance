package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.InteractionLog;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.InteractionLogRepository;
import com.navix.common.exception.BusinessException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CollectionsServiceTest {

    @Mock
    private CollectionCaseRepository caseRepository;
    @Mock
    private InteractionLogRepository interactionRepository;

    private CollectionsService service;

    private final UUID caseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CollectionsService(caseRepository, interactionRepository);
    }

    private CollectionCase existingCase() {
        CollectionCase c = new CollectionCase();
        c.setId(caseId);
        c.setLoanId(UUID.randomUUID());
        return c;
    }

    @Test
    void openCaseReusesExistingCaseForLoan() {
        UUID loanId = UUID.randomUUID();
        CollectionCase existing = existingCase();
        when(caseRepository.findByLoanId(loanId)).thenReturn(Optional.of(existing));

        CollectionCase result = service.openCase(loanId);

        assertThat(result).isSameAs(existing);
    }

    @Test
    void openCaseCreatesNewCaseWhenNoneExists() {
        UUID loanId = UUID.randomUUID();
        when(caseRepository.findByLoanId(loanId)).thenReturn(Optional.empty());
        when(caseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CollectionCase result = service.openCase(loanId);

        assertThat(result.getLoanId()).isEqualTo(loanId);
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
    void assignOfficerSetsOfficerOnCase() {
        UUID officerId = UUID.randomUUID();
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(existingCase()));
        when(caseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CollectionCase result = service.assignOfficer(caseId, officerId);

        assertThat(result.getAssignedOfficerId()).isEqualTo(officerId);
    }
}
