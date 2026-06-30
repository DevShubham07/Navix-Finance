package com.navix.disbursement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.disbursement.domain.DisbursementStatus;
import com.navix.disbursement.entity.ApprovalStep;
import com.navix.disbursement.entity.DisbursementRequest;
import com.navix.disbursement.repository.ApprovalStepRepository;
import com.navix.disbursement.repository.DisbursementRequestRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the maker-checker {@link ApprovalChainService}: the full chain advances through
 * every state with a DIFFERENT actor per step; separation-of-duties blocks the same actor performing
 * two conflicting steps; and illegal transitions are rejected. Repositories are mocked, with the
 * step repository backed by an in-memory list so prior-step SoD lookups behave realistically.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalChainServiceTest {

    @Mock
    private DisbursementRequestRepository requestRepository;
    @Mock
    private ApprovalStepRepository stepRepository;

    private ApprovalChainService service;

    private DisbursementRequest request;
    private final List<ApprovalStep> steps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Legacy chain end-to-end: enable the penny-drop stub (it fails closed by default in prod).
        service = new ApprovalChainService(requestRepository, stepRepository, new PennyDropGate(true));

        request = new DisbursementRequest();
        request.setId(UUID.randomUUID());
        request.setLoanId(UUID.randomUUID());
        request.setStatus(DisbursementStatus.PENDING_CREDIT_REVIEW);

        // Shared fixture stubbings (lenient: not every test exercises every collaborator — some bail
        // out at the state/SoD checks before touching the step repository).
        // Persisted request is found by id; saves echo the argument back.
        lenient().when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        lenient().when(requestRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // The step repository is backed by an in-memory list so recommend()'s step is visible to
        // approveCredit()'s SoD lookup, etc.
        lenient().when(stepRepository.save(any())).thenAnswer(i -> {
            ApprovalStep s = i.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID());
            }
            steps.add(s);
            return s;
        });
        lenient().when(stepRepository.findByDisbursementRequestIdOrderByAtAsc(request.getId()))
                .thenReturn(steps);
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private static void actingAs(String id, String role) {
        ActorContext.set(new CurrentActor(id, id, role));
    }

    @Test
    void fullChainAdvancesThroughAllStatesWithDifferentActors() {
        DisbursementRequest created = service.createRequest(request.getLoanId());
        assertThat(created.getStatus()).isEqualTo(DisbursementStatus.PENDING_CREDIT_REVIEW);

        actingAs("exec-1", "CREDIT_EXECUTIVE");
        assertThat(service.recommend(request.getId()).getStatus())
                .isEqualTo(DisbursementStatus.CREDIT_RECOMMENDED);

        actingAs("head-1", "CREDIT_HEAD");
        assertThat(service.approveCredit(request.getId()).getStatus())
                .isEqualTo(DisbursementStatus.CREDIT_APPROVED);

        actingAs("disb-1", "DISBURSEMENT_HEAD");
        assertThat(service.authoriseRelease(request.getId()).getStatus())
                .isEqualTo(DisbursementStatus.RELEASE_AUTHORISED);

        actingAs("acct-1", "ACCOUNTANT");
        assertThat(service.confirmTransfer(request.getId(), true).getStatus())
                .isEqualTo(DisbursementStatus.TRANSFER_CONFIRMED);

        // Four distinct decisions recorded in the maker-checker trail.
        assertThat(steps).extracting(ApprovalStep::getDecision)
                .containsExactly("RECOMMENDED", "APPROVED", "RELEASED", "CONFIRMED");
        assertThat(steps).extracting(ApprovalStep::getActorId).doesNotHaveDuplicates();
    }

    @Test
    void confirmTransferFailureMovesToTransferFailed() {
        actingAs("exec-1", "CREDIT_EXECUTIVE");
        service.recommend(request.getId());
        actingAs("head-1", "CREDIT_HEAD");
        service.approveCredit(request.getId());
        actingAs("disb-1", "DISBURSEMENT_HEAD");
        service.authoriseRelease(request.getId());

        actingAs("acct-1", "ACCOUNTANT");
        assertThat(service.confirmTransfer(request.getId(), false).getStatus())
                .isEqualTo(DisbursementStatus.TRANSFER_FAILED);
        assertThat(steps).last().extracting(ApprovalStep::getDecision).isEqualTo("FAILED");
    }

    @Test
    void sameActorRecommendingAndApprovingViolatesSeparationOfDuties() {
        actingAs("alice", "CREDIT_EXECUTIVE");
        service.recommend(request.getId());

        // Alice now tries to approve her own recommendation as Credit Head.
        actingAs("alice", "CREDIT_HEAD");
        assertThatThrownBy(() -> service.approveCredit(request.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("SOD_VIOLATION"));

        // The request stays at CREDIT_RECOMMENDED; no APPROVED step was appended.
        assertThat(request.getStatus()).isEqualTo(DisbursementStatus.CREDIT_RECOMMENDED);
        assertThat(steps).extracting(ApprovalStep::getDecision).containsExactly("RECOMMENDED");
    }

    @Test
    void sameActorApprovingAndReleasingViolatesSeparationOfDuties() {
        actingAs("exec-1", "CREDIT_EXECUTIVE");
        service.recommend(request.getId());
        actingAs("bob", "CREDIT_HEAD");
        service.approveCredit(request.getId());

        // Bob (the credit approver) tries to also release.
        actingAs("bob", "DISBURSEMENT_HEAD");
        assertThatThrownBy(() -> service.authoriseRelease(request.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("SOD_VIOLATION"));
        assertThat(request.getStatus()).isEqualTo(DisbursementStatus.CREDIT_APPROVED);
    }

    @Test
    void approveBeforeRecommendIsAnIllegalTransition() {
        // Request is still PENDING_CREDIT_REVIEW — approving is out of order.
        actingAs("head-1", "CREDIT_HEAD");
        assertThatThrownBy(() -> service.approveCredit(request.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("ILLEGAL_TRANSITION"));
    }

    @Test
    void releaseBeforeApproveIsAnIllegalTransition() {
        actingAs("exec-1", "CREDIT_EXECUTIVE");
        service.recommend(request.getId());

        // Only CREDIT_RECOMMENDED — releasing skips the credit-approval step.
        actingAs("disb-1", "DISBURSEMENT_HEAD");
        assertThatThrownBy(() -> service.authoriseRelease(request.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("ILLEGAL_TRANSITION"));
    }

    @Test
    void confirmBeforeReleaseIsAnIllegalTransition() {
        actingAs("exec-1", "CREDIT_EXECUTIVE");
        service.recommend(request.getId());
        actingAs("head-1", "CREDIT_HEAD");
        service.approveCredit(request.getId());

        // CREDIT_APPROVED but not yet released — confirming is premature.
        actingAs("acct-1", "ACCOUNTANT");
        assertThatThrownBy(() -> service.confirmTransfer(request.getId(), true))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("ILLEGAL_TRANSITION"));
    }

    @Test
    void missingRequestThrowsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(requestRepository.findById(unknown)).thenReturn(Optional.empty());
        actingAs("exec-1", "CREDIT_EXECUTIVE");
        assertThatThrownBy(() -> service.recommend(unknown))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
