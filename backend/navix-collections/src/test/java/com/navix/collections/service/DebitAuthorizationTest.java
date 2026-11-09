package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.loan.DebitDirectory;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Authorisation for the manual NACH debit: real money, so every refusal must happen BEFORE the directory is touched. */
class DebitAuthorizationTest {

    private static final long LOAN = 77L;
    private final CollectionCaseRepository cases = mock(CollectionCaseRepository.class);
    private final CollectionsService collections = mock(CollectionsService.class);
    private final DebitDirectory directory = mock(DebitDirectory.class);
    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final ManualDebitService service = new ManualDebitService(cases, collections, directory, flags);
    private final CollectionCase theCase = new CollectionCase();
    private final UUID caseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        theCase.setId(caseId);
        theCase.setLoanId(LOAN);
        when(flags.isEnabled("digio-debit", false)).thenReturn(true);
        when(cases.findByLoanId(LOAN)).thenReturn(Optional.of(theCase));
        when(directory.presentForCase(any(), any(), any()))
                .thenReturn(new DebitDirectory.Result(1L, "SCHEDULED", 500_000L, LocalDate.now(), 1));
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private static void actAs(String role) {
        ActorContext.set(new CurrentActor("9", "Staff", role));
    }

    @Test
    void collectionExecutiveMayDebitOnlyTheirOwnAssignedCase() {
        actAs("COLLECTION_EXECUTIVE");
        org.mockito.Mockito.doThrow(new BusinessException("CASE_NOT_ASSIGNED", "not yours"))
                .when(collections).assertCaseAccessible(caseId);
        assertThatThrownBy(() -> service.debit(LOAN, null))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("CASE_NOT_ASSIGNED");
        verifyNoInteractions(directory);

        org.mockito.Mockito.doNothing().when(collections).assertCaseAccessible(caseId);
        assertThat(service.debit(LOAN, null).debitId()).isEqualTo(1L);
    }

    @Test
    void collectionHeadMayDebitAnyCase() {
        actAs("COLLECTION_HEAD");
        service.debit(LOAN, 100_000L);
        verify(directory).presentForCase(LOAN, 100_000L, 9L);
    }

    @Test
    void otherRolesAreRefusedWithForbiddenRole() {
        for (String role : List.of("CREDIT_EXECUTIVE", "CREDIT_HEAD", "DISBURSEMENT_HEAD", "ACCOUNTANT",
                "TELECALLER", "DSA", "BORROWER", "ANONYMOUS")) {
            actAs(role);
            assertThatThrownBy(() -> service.debit(LOAN, null))
                    .as(role).isInstanceOf(BusinessException.class).extracting("code").isEqualTo("FORBIDDEN_ROLE");
        }
        actAs("ADMIN");
        service.debit(LOAN, null); // the ninth live role: oversight
        verify(directory).presentForCase(eq(LOAN), eq(null), any());
    }

    @Test
    void dsaIsRejected() {
        actAs("DSA");
        assertThatThrownBy(() -> service.debit(LOAN, null))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("FORBIDDEN_ROLE");
        verifyNoInteractions(directory);
        verify(cases, never()).findByLoanId(any());
    }

    @Test
    void flagOffRefusesEvenForAuthorisedStaff() {
        actAs("COLLECTION_HEAD");
        when(flags.isEnabled("digio-debit", false)).thenReturn(false);
        assertThatThrownBy(() -> service.debit(LOAN, null))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("DEBIT_DISABLED");
        verifyNoInteractions(directory);
    }

    @Test
    void theActorAndAmountAreHandedToTheDirectory() {
        // The persisted audit (actor, MANUAL, amount) is asserted in DebitServiceTest.manualDebitPersistsActorTriggerAndAmount.
        actAs("COLLECTION_HEAD");
        service.debit(LOAN, 250_000L);
        verify(directory).presentForCase(LOAN, 250_000L, 9L);
    }

    @Test
    void everyDenialIsLoggedAtWarnWithActorLoanAndCode() {
        ch.qos.logback.classic.Logger lg =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ManualDebitService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        lg.addAppender(appender);
        try {
            actAs("DSA");
            assertThatThrownBy(() -> service.debit(LOAN, 111_111L)).isInstanceOf(BusinessException.class);

            actAs("COLLECTION_HEAD");
            when(flags.isEnabled("digio-debit", false)).thenReturn(false);
            assertThatThrownBy(() -> service.debit(LOAN, null)).isInstanceOf(BusinessException.class);

            when(flags.isEnabled("digio-debit", false)).thenReturn(true);
            when(cases.findByLoanId(LOAN)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.debit(LOAN, null)).isInstanceOf(BusinessException.class);

            when(cases.findByLoanId(LOAN)).thenReturn(Optional.of(theCase));
            org.mockito.Mockito.doThrow(new BusinessException("CASE_NOT_ASSIGNED", "no"))
                    .when(collections).assertCaseAccessible(caseId);
            assertThatThrownBy(() -> service.debit(LOAN, null)).isInstanceOf(BusinessException.class);

            var warns = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(warns).hasSize(4);
            assertThat(warns.get(0)).contains("loanId=77", "actor=9", "code=FORBIDDEN_ROLE");
            assertThat(warns.get(1)).contains("code=DEBIT_DISABLED");
            assertThat(warns.get(2)).contains("code=CASE_NOT_FOUND");
            assertThat(warns.get(3)).contains("code=CASE_NOT_ASSIGNED");
            assertThat(String.join("|", warns)).doesNotContain("111111"); // no amounts in denial lines
        } finally {
            lg.detachAppender(appender);
        }
    }

    // ---- the read surface (history / refund register) ------------------------------------------------

    /** Same case-assignment rule as the debit itself: an executive never reads someone else's case. */
    @Test
    void historyIsScopedToTheExecutivesOwnCase() {
        actAs("COLLECTION_EXECUTIVE");
        org.mockito.Mockito.doThrow(new BusinessException("CASE_NOT_ASSIGNED", "not yours"))
                .when(collections).assertCaseAccessible(caseId);
        assertThatThrownBy(() -> service.history(LOAN))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("CASE_NOT_ASSIGNED");
        verifyNoInteractions(directory);
    }

    /**
     * Fails CLOSED with no case. The scheduled sweep can present a debit on a loan nobody opened a case
     * for, and with no case there is no assignment to check — so an executive must be refused rather than
     * scoped to nothing, while a head still gets the history.
     */
    @Test
    void historyWithNoCaseIsRefusedForAnExecutiveButAllowedForAHead() {
        when(cases.findByLoanId(LOAN)).thenReturn(Optional.empty());

        actAs("COLLECTION_EXECUTIVE");
        assertThatThrownBy(() -> service.history(LOAN))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("CASE_NOT_FOUND");
        verifyNoInteractions(directory);

        actAs("COLLECTION_HEAD");
        when(directory.history(LOAN)).thenReturn(List.of());
        assertThat(service.history(LOAN)).isEmpty();
        verify(directory).history(LOAN);
    }

    /** The refund register is cross-book, so it is not an executive's to read. */
    @Test
    void refundRegisterIsHeadAndAdminOnly() {
        actAs("COLLECTION_EXECUTIVE");
        assertThatThrownBy(() -> service.overpayments())
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("FORBIDDEN_ROLE");
        verifyNoInteractions(directory);

        actAs("ADMIN");
        when(directory.overpayments()).thenReturn(List.of());
        assertThat(service.overpayments()).isEmpty();
    }

    /** Reads are deliberately NOT flag-gated: money already taken is still owed back. */
    @Test
    void readsKeepWorkingAfterTheFlagIsSwitchedOff() {
        when(flags.isEnabled("digio-debit", false)).thenReturn(false);
        actAs("COLLECTION_HEAD");
        when(directory.history(LOAN)).thenReturn(List.of());
        assertThat(service.history(LOAN)).isEmpty();
        assertThatThrownBy(() -> service.debit(LOAN, null))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("DEBIT_DISABLED");
    }
}
