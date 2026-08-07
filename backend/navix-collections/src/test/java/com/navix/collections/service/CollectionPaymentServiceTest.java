package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.collections.dto.CollectionsDtos.CollectionPaymentView;
import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.CollectionPayment;
import com.navix.collections.entity.CollectionPaymentKind;
import com.navix.collections.entity.CollectionPaymentStatus;
import com.navix.collections.entity.Settlement;
import com.navix.collections.entity.SettlementStatus;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.CollectionPaymentRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import java.time.Instant;
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

/**
 * The collections payment chain (V47): who may record money, who checks it, and the one place it
 * reaches the ledger. The rule under test throughout is that <b>collections never books its own
 * collections</b> — only the Accountant's validation moves the borrower's balance.
 */
@ExtendWith(MockitoExtension.class)
class CollectionPaymentServiceTest {

    @Mock
    private CollectionPaymentRepository paymentRepository;
    @Mock
    private CollectionCaseRepository caseRepository;
    @Mock
    private SettlementRepository settlementRepository;
    @Mock
    private LoanDirectory loanDirectory;
    @Mock
    private StaffDirectory staffDirectory;
    @Mock
    private SettlementService settlementService;

    private CollectionPaymentService service;

    private static final CurrentActor OFFICER = new CurrentActor("9", "Sana Khan", "COLLECTION_EXECUTIVE");
    private static final CurrentActor HEAD = new CurrentActor("8", "Arjun Patel", "COLLECTION_HEAD");
    private static final CurrentActor ACCOUNTANT = new CurrentActor("5", "Neha Gupta", "ACCOUNTANT");

    private final UUID caseId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final UUID settlementId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CollectionPaymentService(paymentRepository, caseRepository, settlementRepository,
                settlementService, loanDirectory, staffDirectory, event -> {});
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    @Test
    void aPartPaymentGoesStraightToTheAccountant() {
        ActorContext.set(OFFICER);
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(openCase()));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CollectionPaymentView v = service.raise(caseId, CollectionPaymentKind.PART_PAYMENT,
                250_000L, LocalDate.now(), "UTR-1", "screenshot.png", null);

        assertThat(v.status()).isEqualTo(CollectionPaymentStatus.PENDING_ACCOUNTANT.name());
        // Nothing has touched the loan yet — that is the whole point of the checker.
        verify(loanDirectory, never()).creditCollectionPayment(any(), anyLongValue(), any(), any(), any());
    }

    /** A settlement concedes part of the debt, so the Collection Head stands in front of it. */
    @Test
    void aSettlementPaymentWaitsForTheCollectionHead() {
        ActorContext.set(OFFICER);
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(openCase()));
        Settlement proposed = settlement(SettlementStatus.PROPOSED);
        when(settlementRepository.findByCollectionCaseId(caseId)).thenReturn(List.of(proposed));
        when(settlementRepository.findById(settlementId)).thenReturn(Optional.of(proposed));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CollectionPaymentView v = service.raise(caseId, CollectionPaymentKind.SETTLEMENT,
                500_000L, LocalDate.now(), "UTR-2", null, null);

        assertThat(v.status()).isEqualTo(CollectionPaymentStatus.PENDING_HEAD.name());
        assertThat(v.settlementId()).isEqualTo(settlementId);
    }

    /** With the concession already approved there is nothing left for the Head to decide. */
    @Test
    void aSettlementPaymentAgainstAnApprovedSettlementSkipsTheHead() {
        ActorContext.set(OFFICER);
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(openCase()));
        Settlement approved = settlement(SettlementStatus.APPROVED);
        when(settlementRepository.findByCollectionCaseId(caseId)).thenReturn(List.of(approved));
        when(settlementRepository.findById(settlementId)).thenReturn(Optional.of(approved));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CollectionPaymentView v = service.raise(caseId, CollectionPaymentKind.SETTLEMENT,
                500_000L, LocalDate.now(), "UTR-3", null, null);

        assertThat(v.status()).isEqualTo(CollectionPaymentStatus.PENDING_ACCOUNTANT.name());
    }

    /** "Settlement" must not become a way to write down a balance nobody agreed to. */
    @Test
    void aSettlementPaymentWithNoSettlementOnTheCaseIsRefused() {
        ActorContext.set(OFFICER);
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(openCase()));
        when(settlementRepository.findByCollectionCaseId(caseId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.raise(caseId, CollectionPaymentKind.SETTLEMENT,
                500_000L, LocalDate.now(), null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Propose a settlement");
    }

    @Test
    void theAccountantsValidationIsWhatCreditsTheLoan() {
        ActorContext.set(ACCOUNTANT);
        CollectionPayment p = pending(CollectionPaymentStatus.PENDING_ACCOUNTANT);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loanDirectory.creditCollectionPayment(eq(2L), eq(250_000L), any(), any(), any()))
                .thenReturn(77L);

        CollectionPaymentView v = service.validate(paymentId, true, "matched on statement");

        assertThat(v.status()).isEqualTo(CollectionPaymentStatus.VALIDATED.name());
        assertThat(v.ledgerPaymentId()).isEqualTo(77L);
        assertThat(v.validatedBy()).isEqualTo(5L);
    }

    /** The ledger link is the idempotency guard: a re-validated row must not credit twice. */
    @Test
    void validatingAnAlreadyCreditedPaymentDoesNotCreditItAgain() {
        ActorContext.set(ACCOUNTANT);
        CollectionPayment p = pending(CollectionPaymentStatus.PENDING_ACCOUNTANT);
        p.setLedgerPaymentId(77L);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.validate(paymentId, true, null);

        verify(loanDirectory, never()).creditCollectionPayment(any(), anyLongValue(), any(), any(), any());
    }

    /** A rejection the officer can't act on is useless, so remarks are mandatory. */
    @Test
    void rejectingWithoutRemarksIsRefused() {
        ActorContext.set(ACCOUNTANT);
        when(paymentRepository.findById(paymentId))
                .thenReturn(Optional.of(pending(CollectionPaymentStatus.PENDING_ACCOUNTANT)));

        assertThatThrownBy(() -> service.validate(paymentId, false, "  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("why you are rejecting");
    }

    /** A settlement payment cannot jump the Collection Head. */
    @Test
    void theAccountantCannotValidateBeforeTheHeadHasApproved() {
        ActorContext.set(ACCOUNTANT);
        when(paymentRepository.findById(paymentId))
                .thenReturn(Optional.of(pending(CollectionPaymentStatus.PENDING_HEAD)));

        assertThatThrownBy(() -> service.validate(paymentId, true, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Collection Head has not approved");
    }

    /**
     * Releasing the payment approves the concession behind it. Without this the settlement stayed
     * PROPOSED, never capped the payable, and a paid full-and-final left the loan open for the
     * difference it was meant to write off.
     */
    @Test
    void releasingASettlementPaymentAlsoApprovesTheSettlement() {
        ActorContext.set(HEAD);
        CollectionPayment p = pending(CollectionPaymentStatus.PENDING_HEAD);
        p.setSettlementId(settlementId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(settlementRepository.findById(settlementId))
                .thenReturn(Optional.of(settlement(SettlementStatus.PROPOSED)));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.headApprove(paymentId);

        verify(settlementService).approve(settlementId);
    }

    /** …but an already-approved concession is not re-approved. */
    @Test
    void releasingAgainstAnApprovedSettlementDoesNotReApproveIt() {
        ActorContext.set(HEAD);
        CollectionPayment p = pending(CollectionPaymentStatus.PENDING_HEAD);
        p.setSettlementId(settlementId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(settlementRepository.findById(settlementId))
                .thenReturn(Optional.of(settlement(SettlementStatus.APPROVED)));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.headApprove(paymentId);

        verify(settlementService, never()).approve(any());
    }

    /** Maker-checker: the Head who recorded a payment cannot be the Head who releases it. */
    @Test
    void theHeadWhoRecordedThePaymentCannotApproveIt() {
        ActorContext.set(HEAD);
        CollectionPayment p = pending(CollectionPaymentStatus.PENDING_HEAD);
        p.setRaisedBy(8L); // the acting head
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.headApprove(paymentId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void collectionsStaffCannotValidateTheirOwnCollections() {
        ActorContext.set(OFFICER);

        assertThatThrownBy(() -> service.validate(paymentId, true, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ACCOUNTANT");
    }

    // ---- fixtures -----------------------------------------------------------------------

    private CollectionCase openCase() {
        CollectionCase c = new CollectionCase();
        c.setId(caseId);
        c.setLoanId(2L);
        return c;
    }

    private Settlement settlement(SettlementStatus status) {
        Settlement s = new Settlement();
        s.setId(settlementId);
        s.setCollectionCaseId(caseId);
        s.setSettlementAmount(500_000L);
        s.setProposedBy(9L);
        s.setStatus(status);
        s.setCreatedAt(Instant.now());
        return s;
    }

    private CollectionPayment pending(CollectionPaymentStatus status) {
        CollectionPayment p = new CollectionPayment();
        p.setId(paymentId);
        p.setCollectionCaseId(caseId);
        p.setLoanId(2L);
        p.setKind(status == CollectionPaymentStatus.PENDING_HEAD
                ? CollectionPaymentKind.SETTLEMENT : CollectionPaymentKind.PART_PAYMENT);
        p.setAmountPaise(250_000L);
        p.setPaidOn(LocalDate.now());
        p.setStatus(status);
        p.setRaisedBy(9L);
        p.setRaisedAt(Instant.now());
        return p;
    }

    /** {@code creditCollectionPayment} takes a primitive long — matchers must match that. */
    private static long anyLongValue() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
