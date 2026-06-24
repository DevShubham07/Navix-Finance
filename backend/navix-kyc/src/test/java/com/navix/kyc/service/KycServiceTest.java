package com.navix.kyc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.kyc.domain.KycCheckResult;
import com.navix.kyc.domain.KycCheckType;
import com.navix.kyc.domain.KycStatus;
import com.navix.kyc.entity.KycCase;
import com.navix.kyc.entity.KycCheck;
import com.navix.kyc.repository.DigiLockerSessionRepository;
import com.navix.kyc.repository.KycCaseRepository;
import com.navix.kyc.repository.KycCheckRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KycServiceTest {

    @Mock
    private KycCaseRepository kycCaseRepository;
    @Mock
    private KycCheckRepository kycCheckRepository;
    @Mock
    private DigiLockerSessionRepository digiLockerSessionRepository;

    private KycService kycService;

    /** In-test store standing in for the kyc_check table. */
    private final List<KycCheck> checks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        kycService = new KycService(kycCaseRepository, kycCheckRepository, digiLockerSessionRepository);
    }

    private KycCase openCaseStub() {
        when(kycCaseRepository.findByBorrowerId(1L)).thenReturn(Optional.empty());
        when(kycCaseRepository.save(any())).thenAnswer(i -> {
            KycCase c = i.getArgument(0);
            if (c.getId() == null) {
                c.setId(50L);
            }
            return c;
        });
        return kycService.openCase(1L);
    }

    @Test
    void openCaseCreatesPendingCase() {
        KycCase kycCase = openCaseStub();

        assertThat(kycCase.getId()).isEqualTo(50L);
        assertThat(kycCase.getBorrowerId()).isEqualTo(1L);
        assertThat(kycCase.getStatus()).isEqualTo(KycStatus.PENDING.name());
    }

    @Test
    void openCaseIsIdempotent() {
        KycCase existing = new KycCase();
        existing.setId(50L);
        existing.setBorrowerId(1L);
        existing.setStatus(KycStatus.IN_REVIEW.name());
        when(kycCaseRepository.findByBorrowerId(1L)).thenReturn(Optional.of(existing));

        assertThat(kycService.openCase(1L)).isSameAs(existing);
    }

    @Test
    void passingCheckKeepsCasePending() {
        KycCase kycCase = caseInStatus(KycStatus.PENDING);
        wireChecksStore(kycCase.getId());

        kycService.submitCheck(1L, KycCheckType.PAN, KycCheckResult.PASS, null);

        assertThat(kycCase.getStatus()).isEqualTo(KycStatus.PENDING.name());
        assertThat(checks).hasSize(1);
    }

    @Test
    void reviewCheckMovesCaseToInReview() {
        KycCase kycCase = caseInStatus(KycStatus.PENDING);
        wireChecksStore(kycCase.getId());

        kycService.submitCheck(1L, KycCheckType.ADDRESS, KycCheckResult.REVIEW, null);

        assertThat(kycCase.getStatus()).isEqualTo(KycStatus.IN_REVIEW.name());
    }

    @Test
    void failingCheckRejectsCase() {
        KycCase kycCase = caseInStatus(KycStatus.PENDING);
        wireChecksStore(kycCase.getId());

        kycService.submitCheck(1L, KycCheckType.AADHAAR, KycCheckResult.FAIL, null);

        assertThat(kycCase.getStatus()).isEqualTo(KycStatus.REJECTED.name());
    }

    @Test
    void approveSetsApprovedWhenNoFailures() {
        KycCase kycCase = caseInStatus(KycStatus.IN_REVIEW);
        wireChecksStore(kycCase.getId());
        checks.add(check(kycCase.getId(), KycCheckResult.REVIEW));

        KycCase approved = kycService.approve(1L);

        assertThat(approved.getStatus()).isEqualTo(KycStatus.APPROVED.name());
    }

    @Test
    void approveRejectedWhenAFailedCheckExists() {
        KycCase kycCase = caseInStatus(KycStatus.PENDING);
        wireChecksStore(kycCase.getId());
        checks.add(check(kycCase.getId(), KycCheckResult.FAIL));

        assertThatThrownBy(() -> kycService.approve(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("failed check");
    }

    @Test
    void rejectSetsRejected() {
        KycCase kycCase = caseInStatus(KycStatus.IN_REVIEW);

        KycCase rejected = kycService.reject(1L);

        assertThat(rejected.getStatus()).isEqualTo(KycStatus.REJECTED.name());
    }

    @Test
    void submitCheckOnTerminalCaseIsRejected() {
        caseInStatus(KycStatus.APPROVED);

        assertThatThrownBy(() -> kycService.submitCheck(1L, KycCheckType.PAN, KycCheckResult.PASS, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already");
    }

    @Test
    void getCaseThrowsWhenMissing() {
        when(kycCaseRepository.findByBorrowerId(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> kycService.getCase(2L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- helpers ---

    /** Register a case for borrower 1 in the given status and make case saves echo. */
    private KycCase caseInStatus(KycStatus status) {
        KycCase kycCase = new KycCase();
        kycCase.setId(50L);
        kycCase.setBorrowerId(1L);
        kycCase.setStatus(status.name());
        when(kycCaseRepository.findByBorrowerId(1L)).thenReturn(Optional.of(kycCase));
        lenient().when(kycCaseRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        return kycCase;
    }

    /** Back kyc_check reads/writes with the in-test list. */
    private void wireChecksStore(Long kycCaseId) {
        lenient().when(kycCheckRepository.save(any())).thenAnswer(i -> {
            KycCheck c = i.getArgument(0);
            checks.add(c);
            return c;
        });
        lenient().when(kycCheckRepository.findByKycCaseId(kycCaseId)).thenReturn(checks);
    }

    private static KycCheck check(Long kycCaseId, KycCheckResult result) {
        KycCheck c = new KycCheck();
        c.setKycCaseId(kycCaseId);
        c.setType(KycCheckType.PAN);
        c.setResult(result.name());
        return c;
    }
}
