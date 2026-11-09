package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.CollectionPayment;
import com.navix.collections.entity.CollectionPaymentKind;
import com.navix.collections.entity.CollectionPaymentStatus;
import com.navix.collections.entity.Settlement;
import com.navix.collections.entity.SettlementStatus;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.CollectionPaymentRepository;
import com.navix.collections.repository.InteractionLogRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.collections.CollectionActivityDirectory.StaffActivity;
import com.navix.common.collections.CollectionStaffStats;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The collections half of the staff-performance dashboard. What matters is attribution: each figure
 * lands on the staffer who actually did the thing (recorded vs validated a payment, proposed vs
 * decided a settlement), and work outside the window or on a settled loan is not counted.
 */
@ExtendWith(MockitoExtension.class)
class CollectionActivityDirectoryAdapterTest {

    private static final long EXEC = 60L;
    private static final long HEAD = 61L;
    private static final long ACCOUNTANT = 62L;

    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant IN = Instant.parse("2026-08-10T06:00:00Z");
    private static final Instant BEFORE = FROM.minus(3, ChronoUnit.DAYS);

    @Mock
    private InteractionLogRepository interactionRepository;
    @Mock
    private CollectionPaymentRepository paymentRepository;
    @Mock
    private SettlementRepository settlementRepository;
    @Mock
    private CollectionCaseRepository caseRepository;
    @Mock
    private LoanDirectory loanDirectory;

    private CollectionActivityDirectoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CollectionActivityDirectoryAdapter(
                interactionRepository, paymentRepository, settlementRepository, caseRepository, loanDirectory);
        lenient().when(interactionRepository.countByStaffAndOutcomeInWindow(anyCollection(), any(), any()))
                .thenReturn(List.of());
        lenient().when(interactionRepository.activityByStaffInWindow(anyCollection(), any(), any()))
                .thenReturn(List.of());
        lenient().when(paymentRepository.findTouchedByStaffInWindow(anyCollection(), any(), any()))
                .thenReturn(List.of());
        lenient().when(settlementRepository.findTouchedByStaffInWindow(anyCollection(), any(), any()))
                .thenReturn(List.of());
        lenient().when(caseRepository.findByAssignedOfficerIdIn(anyCollection())).thenReturn(List.of());
    }

    private static InteractionLogRepository.StaffOutcomeCount outcome(long staffId, String outcome, long count) {
        return new InteractionLogRepository.StaffOutcomeCount() {
            @Override
            public Long getStaffId() {
                return staffId;
            }

            @Override
            public String getOutcome() {
                return outcome;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
    }

    private static CollectionPayment payment(long raisedBy, Instant raisedAt, CollectionPaymentStatus status,
                                             long amount, Long validatedBy, Instant validatedAt) {
        CollectionPayment p = new CollectionPayment();
        p.setCollectionCaseId(UUID.randomUUID());
        p.setLoanId(1L);
        p.setKind(CollectionPaymentKind.PART_PAYMENT);
        p.setAmountPaise(amount);
        p.setRaisedBy(raisedBy);
        p.setRaisedAt(raisedAt);
        p.setStatus(status);
        p.setValidatedBy(validatedBy);
        p.setValidatedAt(validatedAt);
        return p;
    }

    private static LoanSummary loan(long id, String status) {
        return new LoanSummary(id, 7L, 1L, status, 800_000L, 705_600L, 1_040_000L, 1_040_000L,
                null, null, "B", null, null, null, null, null, null, false);
    }

    @Test
    void emptyRosterTouchesNothing() {
        assertThat(adapter.statsByStaff(List.of(), FROM, TO)).isEmpty();
        verifyNoInteractions(interactionRepository, paymentRepository, settlementRepository, caseRepository);
    }

    @Test
    void bucketsInteractionsByOutcome() {
        when(interactionRepository.countByStaffAndOutcomeInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                outcome(EXEC, "CONNECTED", 4), outcome(EXEC, "PROMISE_TO_PAY", 2),
                outcome(EXEC, "no_answer", 3), outcome(EXEC, "SOMETHING_NEW", 1)));

        CollectionStaffStats s = adapter.statsByStaff(List.of(EXEC), FROM, TO).get(EXEC).stats();

        assertThat(s.interactions()).isEqualTo(10);
        assertThat(s.connected()).isEqualTo(4);
        assertThat(s.promisesToPay()).isEqualTo(2);
        assertThat(s.noAnswer()).as("outcome match is case-insensitive").isEqualTo(3);
    }

    @Test
    void creditsRecordingToTheOfficerAndValidationToTheAccountant() {
        when(paymentRepository.findTouchedByStaffInWindow(anyCollection(), any(), any())).thenReturn(List.of(
                payment(EXEC, IN, CollectionPaymentStatus.VALIDATED, 100_000, ACCOUNTANT, IN),
                payment(EXEC, IN, CollectionPaymentStatus.PENDING_ACCOUNTANT, 40_000, null, null),
                payment(EXEC, IN, CollectionPaymentStatus.REJECTED, 25_000, ACCOUNTANT, IN),
                // Recorded before the window, validated inside it: the Accountant's work, not the officer's.
                payment(EXEC, BEFORE, CollectionPaymentStatus.VALIDATED, 70_000, ACCOUNTANT, IN)));

        Map<Long, StaffActivity> stats = adapter.statsByStaff(List.of(EXEC, ACCOUNTANT), FROM, TO);
        CollectionStaffStats exec = stats.get(EXEC).stats();
        CollectionStaffStats acct = stats.get(ACCOUNTANT).stats();

        assertThat(exec.paymentsRaised()).isEqualTo(3);
        assertThat(exec.paymentsRaisedPaise()).isEqualTo(165_000);
        assertThat(exec.paymentsRecovered()).isEqualTo(1);
        assertThat(exec.paymentsRecoveredPaise()).isEqualTo(100_000);
        assertThat(exec.paymentsPending()).isEqualTo(1);
        assertThat(exec.paymentsRejected()).isEqualTo(1);
        assertThat(acct.paymentsValidated()).isEqualTo(2);
        assertThat(acct.paymentsValidatedPaise()).isEqualTo(170_000);
        assertThat(acct.paymentsValidationRejected()).isEqualTo(1);
        assertThat(acct.paymentsRaised()).isZero();
    }

    @Test
    void separatesTheProposerFromTheDecidingHead() {
        Settlement approved = new Settlement();
        approved.setSettlementAmount(300_000L);
        approved.setStatus(SettlementStatus.APPROVED);
        approved.setProposedBy(EXEC);
        approved.setCreatedAt(IN);
        approved.setApprovedBy(HEAD);
        approved.setApprovedAt(IN);
        Settlement rejected = new Settlement();
        rejected.setSettlementAmount(90_000L);
        rejected.setStatus(SettlementStatus.REJECTED);
        rejected.setProposedBy(EXEC);
        rejected.setCreatedAt(IN);
        rejected.setRejectedBy(HEAD);
        rejected.setRejectedAt(IN);
        when(settlementRepository.findTouchedByStaffInWindow(anyCollection(), any(), any()))
                .thenReturn(List.of(approved, rejected));

        Map<Long, StaffActivity> stats = adapter.statsByStaff(List.of(EXEC, HEAD), FROM, TO);

        assertThat(stats.get(EXEC).stats().settlementsProposed()).isEqualTo(2);
        assertThat(stats.get(EXEC).stats().settlementsProposedPaise()).isEqualTo(390_000);
        assertThat(stats.get(EXEC).stats().settlementsApproved()).isZero();
        assertThat(stats.get(HEAD).stats().settlementsApproved()).isEqualTo(1);
        assertThat(stats.get(HEAD).stats().settlementsApprovedPaise()).isEqualTo(300_000);
        assertThat(stats.get(HEAD).stats().settlementsRejected()).isEqualTo(1);
        assertThat(stats.get(HEAD).activityAt()).hasSize(2);
    }

    @Test
    void countsOnlyOpenCasesOnTheDesk() {
        CollectionCase open = new CollectionCase();
        open.setLoanId(1L);
        open.setAssignedOfficerId(EXEC);
        CollectionCase settled = new CollectionCase();
        settled.setLoanId(2L);
        settled.setAssignedOfficerId(EXEC);
        when(caseRepository.findByAssignedOfficerIdIn(anyCollection())).thenReturn(List.of(open, settled));
        when(loanDirectory.findLoans(anyCollection())).thenReturn(Map.of(1L, loan(1L, "OVERDUE"), 2L, loan(2L, "CLOSED")));

        assertThat(adapter.statsByStaff(List.of(EXEC), FROM, TO).get(EXEC).stats().casesNow()).isEqualTo(1);
    }
}
