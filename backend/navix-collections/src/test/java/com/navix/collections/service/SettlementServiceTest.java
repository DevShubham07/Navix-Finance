package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.navix.collections.entity.Settlement;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock
    private SettlementRepository settlementRepository;
    @Mock
    private CollectionCaseRepository caseRepository;

    private SettlementService service;

    private static final CurrentActor OFFICER =
            new CurrentActor("officer-1", "Olivia Officer", "COLLECTION_EXECUTIVE");
    private static final CurrentActor HEAD =
            new CurrentActor("head-1", "Henry Head", "COLLECTION_HEAD");

    private final UUID caseId = UUID.randomUUID();
    private final UUID settlementId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SettlementService(settlementRepository, caseRepository);
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    @Test
    void proposeRecordsAmountAndProposerUuid() {
        ActorContext.set(OFFICER);
        when(caseRepository.existsById(caseId)).thenReturn(true);
        when(settlementRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Settlement s = service.propose(caseId, 500_000L);

        assertThat(s.getCollectionCaseId()).isEqualTo(caseId);
        assertThat(s.getSettlementAmount()).isEqualTo(500_000L);
        assertThat(s.getProposedBy()).isNotNull();
        assertThat(s.getApprovedBy()).isNull();
        assertThat(s.getCreatedAt()).isNotNull();
    }

    @Test
    void proposeThenApproveByDifferentActorSucceeds() {
        // Officer proposes
        ActorContext.set(OFFICER);
        when(caseRepository.existsById(caseId)).thenReturn(true);
        when(settlementRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        Settlement proposed = service.propose(caseId, 500_000L);

        // Head approves
        ActorContext.set(HEAD);
        proposed.setId(settlementId);
        when(settlementRepository.findById(settlementId)).thenReturn(Optional.of(proposed));

        Settlement approved = service.approve(settlementId);

        assertThat(approved.getApprovedBy()).isNotNull();
        assertThat(approved.getApprovedBy()).isNotEqualTo(approved.getProposedBy());
        assertThat(approved.getApprovedAt()).isNotNull();
    }

    @Test
    void approveBySameActorWhoProposedViolatesSod() {
        // Officer proposes
        ActorContext.set(OFFICER);
        when(caseRepository.existsById(caseId)).thenReturn(true);
        when(settlementRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        Settlement proposed = service.propose(caseId, 500_000L);
        proposed.setId(settlementId);

        // Same officer attempts to approve their own proposal
        when(settlementRepository.findById(settlementId)).thenReturn(Optional.of(proposed));

        assertThatThrownBy(() -> service.approve(settlementId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("separation of duties");
    }
}
