package com.navix.kyc.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.kyc.domain.KycCheckResult;
import com.navix.kyc.domain.KycCheckType;
import com.navix.kyc.domain.KycStatus;
import com.navix.kyc.entity.DigiLockerSession;
import com.navix.kyc.entity.KycCase;
import com.navix.kyc.entity.KycCheck;
import com.navix.kyc.repository.DigiLockerSessionRepository;
import com.navix.kyc.repository.KycCaseRepository;
import com.navix.kyc.repository.KycCheckRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates a borrower's KYC case and its individual checks.
 *
 * <p>SELF-CONTAINED for the demo: check results are supplied via the API rather than pulled from
 * navix-verification, so this service has no dependency on that module. It opens a case, records
 * PAN/AADHAAR/SELFIE/ADDRESS check outcomes, persists DigiLocker sessions, and recomputes the
 * overall case {@link KycStatus} from the recorded checks. An approver may finally approve/reject.
 *
 * <p>Status rule: any FAIL ⇒ REJECTED; otherwise any REVIEW ⇒ IN_REVIEW; otherwise PENDING until
 * an approver decision lands. APPROVED/REJECTED are terminal and set only by the approver.
 */
@Service
@RequiredArgsConstructor
public class KycService {

    private final KycCaseRepository kycCaseRepository;
    private final KycCheckRepository kycCheckRepository;
    private final DigiLockerSessionRepository digiLockerSessionRepository;

    /** Open the KYC case for a borrower, or return the existing one (idempotent start). */
    @Transactional
    public KycCase openCase(Long borrowerId) {
        return kycCaseRepository.findByBorrowerId(borrowerId)
                .orElseGet(() -> {
                    KycCase kycCase = new KycCase();
                    kycCase.setBorrowerId(borrowerId);
                    kycCase.setStatus(KycStatus.PENDING.name());
                    return kycCaseRepository.save(kycCase);
                });
    }

    /** Fetch the active case for a borrower, 404 if none. */
    @Transactional(readOnly = true)
    public KycCase getCase(Long borrowerId) {
        return kycCaseRepository.findByBorrowerId(borrowerId)
                .orElseThrow(() -> new ResourceNotFoundException("KycCase", "borrowerId=" + borrowerId));
    }

    /** All checks recorded against a case (ordered by the repository's natural order). */
    @Transactional(readOnly = true)
    public List<KycCheck> getChecks(Long kycCaseId) {
        return kycCheckRepository.findByKycCaseId(kycCaseId);
    }

    /**
     * Record a check result against a borrower's case and recompute the overall status. A
     * terminal (APPROVED/REJECTED) case rejects further check submissions.
     */
    @Transactional
    public KycCheck submitCheck(Long borrowerId, KycCheckType type, KycCheckResult result,
                                BigDecimal score) {
        KycCase kycCase = getCase(borrowerId);
        assertNotTerminal(kycCase);

        KycCheck check = new KycCheck();
        check.setKycCaseId(kycCase.getId());
        check.setType(type);
        check.setResult(result.name());
        check.setScore(score);
        KycCheck saved = kycCheckRepository.save(check);

        recomputeStatus(kycCase);
        return saved;
    }

    /** Persist a DigiLocker session outcome for the borrower (full Aadhaar is never stored). */
    @Transactional
    public DigiLockerSession recordDigiLockerSession(Long borrowerId, String clientId, String status,
                                                     boolean aadhaarLinked) {
        DigiLockerSession session = new DigiLockerSession();
        session.setBorrowerId(borrowerId);
        session.setClientId(clientId);
        session.setStatus(status);
        session.setAadhaarLinked(aadhaarLinked);
        return digiLockerSessionRepository.save(session);
    }

    /** Approver decision: APPROVE the case (terminal). Rejected if any check has FAILED. */
    @Transactional
    public KycCase approve(Long borrowerId) {
        KycCase kycCase = getCase(borrowerId);
        assertNotTerminal(kycCase);
        if (hasResult(kycCase.getId(), KycCheckResult.FAIL)) {
            throw new BusinessException("KYC_HAS_FAILED_CHECK",
                    "Cannot approve a case with a failed check");
        }
        kycCase.setStatus(KycStatus.APPROVED.name());
        return kycCaseRepository.save(kycCase);
    }

    /** Approver decision: REJECT the case (terminal). */
    @Transactional
    public KycCase reject(Long borrowerId) {
        KycCase kycCase = getCase(borrowerId);
        assertNotTerminal(kycCase);
        kycCase.setStatus(KycStatus.REJECTED.name());
        return kycCaseRepository.save(kycCase);
    }

    /**
     * Recompute and persist a case's status from its recorded checks (non-terminal cases only):
     * any FAIL ⇒ REJECTED; else any REVIEW ⇒ IN_REVIEW; else PENDING.
     */
    @Transactional
    public KycCase evaluate(Long kycCaseId) {
        KycCase kycCase = kycCaseRepository.findById(kycCaseId)
                .orElseThrow(() -> new ResourceNotFoundException("KycCase", String.valueOf(kycCaseId)));
        recomputeStatus(kycCase);
        return kycCase;
    }

    private void recomputeStatus(KycCase kycCase) {
        if (isTerminal(kycCase)) {
            return;
        }
        List<KycCheck> checks = kycCheckRepository.findByKycCaseId(kycCase.getId());
        KycStatus status = KycStatus.PENDING;
        boolean anyReview = false;
        for (KycCheck check : checks) {
            if (KycCheckResult.FAIL.name().equals(check.getResult())) {
                status = KycStatus.REJECTED;
                break;
            }
            if (KycCheckResult.REVIEW.name().equals(check.getResult())) {
                anyReview = true;
            }
        }
        if (status != KycStatus.REJECTED && anyReview) {
            status = KycStatus.IN_REVIEW;
        }
        kycCase.setStatus(status.name());
        kycCaseRepository.save(kycCase);
    }

    private boolean hasResult(Long kycCaseId, KycCheckResult result) {
        return kycCheckRepository.findByKycCaseId(kycCaseId).stream()
                .anyMatch(c -> result.name().equals(c.getResult()));
    }

    private void assertNotTerminal(KycCase kycCase) {
        if (isTerminal(kycCase)) {
            throw new BusinessException("KYC_CASE_TERMINAL",
                    "KYC case is already " + kycCase.getStatus());
        }
    }

    private static boolean isTerminal(KycCase kycCase) {
        return KycStatus.APPROVED.name().equals(kycCase.getStatus())
                || KycStatus.REJECTED.name().equals(kycCase.getStatus());
    }
}
