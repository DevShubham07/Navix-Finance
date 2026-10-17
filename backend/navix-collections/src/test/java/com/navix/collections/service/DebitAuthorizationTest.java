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
    void everyAttemptIsAudited() {
        actAs("COLLECTION_HEAD");
        service.debit(LOAN, 250_000L);
        // actor id + amount reach the directory, which records them with Trigger.MANUAL on the debit row.
        verify(directory).presentForCase(LOAN, 250_000L, 9L);
    }
}
