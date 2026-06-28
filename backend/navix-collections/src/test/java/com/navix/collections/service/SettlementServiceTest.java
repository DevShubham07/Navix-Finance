package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.navix.collections.dto.CollectionsDtos.SettlementView;
import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.Settlement;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import java.time.Instant;
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
    @Mock
    private StaffDirectory staffDirectory;
    @Mock
    private LoanDirectory loanDirectory;

    private SettlementService service;

    // Real staff ids now (bigint), as injected by the resolved staff session.
    private static final CurrentActor OFFICER =
            new CurrentActor("9", "Sana Khan", "COLLECTION_EXECUTIVE");
    private static final CurrentActor HEAD =
            new CurrentActor("8", "Arjun Patel", "COLLECTION_HEAD");

    private final UUID caseId = UUID.randomUUID();
    private final UUID settlementId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SettlementService(settlementRepository, caseRepository, staffDirectory,
                loanDirectory, event -> {});
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private CollectionCase caseWithLoan() {
        CollectionCase c = new CollectionCase();
        c.setId(caseId);
        c.setLoanId(2L);
        return c;
    }

    private Settlement proposedByOfficer() {
        Settlement s = new Settlement();
        s.setId(settlementId);
        s.setCollectionCaseId(caseId);
        s.setSettlementAmount(500_000L);
        s.setProposedBy(9L);
        s.setCreatedAt(Instant.now());
        return s;
    }

    @Test
    void proposeRecordsAmountAndRealProposer() {
        ActorContext.set(OFFICER);
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseWithLoan()));
        when(settlementRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(staffDirectory.findStaff(9L))
                .thenReturn(Optional.of(new StaffSummary(9L, "Sana Khan", "COLLECTION_EXECUTIVE", true)));

        SettlementView s = service.propose(caseId, 500_000L);

        assertThat(s.collectionCaseId()).isEqualTo(caseId);
        assertThat(s.settlementAmountPaise()).isEqualTo(500_000L);
        assertThat(s.proposedBy()).isEqualTo(9L);
        assertThat(s.proposedByName()).isEqualTo("Sana Khan");
        assertThat(s.approvedBy()).isNull();
        assertThat(s.createdAt()).isNotNull();
    }

    @Test
    void proposeWithNonNumericActorIsRejected() {
        ActorContext.set(new CurrentActor("staff-COLLECTION_EXECUTIVE", "Demo", "COLLECTION_EXECUTIVE"));
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseWithLoan()));

        assertThatThrownBy(() -> service.propose(caseId, 500_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("staff");
    }

    @Test
    void approveByDifferentActorSucceeds() {
        ActorContext.set(HEAD);
        when(settlementRepository.findById(settlementId)).thenReturn(Optional.of(proposedByOfficer()));
        when(settlementRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(staffDirectory.findStaff(9L))
                .thenReturn(Optional.of(new StaffSummary(9L, "Sana Khan", "COLLECTION_EXECUTIVE", true)));
        when(staffDirectory.findStaff(8L))
                .thenReturn(Optional.of(new StaffSummary(8L, "Arjun Patel", "COLLECTION_HEAD", true)));

        SettlementView approved = service.approve(settlementId);

        assertThat(approved.approvedBy()).isEqualTo(8L);
        assertThat(approved.approvedByName()).isEqualTo("Arjun Patel");
        assertThat(approved.proposedBy()).isEqualTo(9L);
        assertThat(approved.approvedAt()).isNotNull();
    }

    @Test
    void approveBySameActorWhoProposedViolatesSod() {
        // A Collection Head may also propose; if that same head then approves, SoD must block it
        // (the role guard passes, so we reach the proposer≠approver check).
        ActorContext.set(HEAD); // id 8
        Settlement proposedByHead = proposedByOfficer();
        proposedByHead.setProposedBy(8L); // same staff id as the approver
        when(settlementRepository.findById(settlementId)).thenReturn(Optional.of(proposedByHead));

        assertThatThrownBy(() -> service.approve(settlementId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("separation of duties");
    }

    @Test
    void approveByNonHeadIsForbidden() {
        // Only a Collection Head (or ADMIN) may approve — an executive is rejected up front.
        ActorContext.set(OFFICER);

        assertThatThrownBy(() -> service.approve(settlementId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("COLLECTION_HEAD");
    }
}
