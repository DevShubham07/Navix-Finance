package com.navix.kyc.dto;

import com.navix.kyc.domain.KycCheckResult;
import com.navix.kyc.domain.KycCheckType;
import com.navix.kyc.entity.DigiLockerSession;
import com.navix.kyc.entity.KycCase;
import com.navix.kyc.entity.KycCheck;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request/response DTOs for the KYC module. Views are mapped from entities via the static
 * {@code of(...)} factories. Mirrors LoanDtos.
 *
 * <p>For the demo, check results are submitted via the API (KYC stays self-contained and does
 * not call navix-verification).
 */
public final class KycDtos {

    private KycDtos() {
        // container for nested DTO records
    }

    /** Open (or fetch) the KYC case for a borrower. */
    public record StartCaseRequest(
            @NotNull Long borrowerId) {
    }

    /**
     * Submit a single check result into a case. {@code result} is one of PASS/FAIL/REVIEW;
     * {@code score} is an optional match/confidence value.
     */
    public record SubmitCheckRequest(
            @NotNull KycCheckType type,
            @NotNull KycCheckResult result,
            BigDecimal score) {
    }

    /** Record a DigiLocker session outcome against the case's borrower. */
    public record DigiLockerSessionRequest(
            String clientId,
            String status,
            boolean aadhaarLinked) {
    }

    public record CheckView(
            Long id,
            Long kycCaseId,
            KycCheckType type,
            String result,
            BigDecimal score) {

        public static CheckView of(KycCheck c) {
            return new CheckView(c.getId(), c.getKycCaseId(), c.getType(), c.getResult(), c.getScore());
        }
    }

    public record DigiLockerSessionView(
            Long id,
            Long borrowerId,
            String clientId,
            String status,
            boolean aadhaarLinked) {

        public static DigiLockerSessionView of(DigiLockerSession s) {
            return new DigiLockerSessionView(s.getId(), s.getBorrowerId(), s.getClientId(),
                    s.getStatus(), s.isAadhaarLinked());
        }
    }

    /** Full case view: the case header plus all recorded checks. */
    public record CaseView(
            Long id,
            Long borrowerId,
            String status,
            List<CheckView> checks) {

        public static CaseView of(KycCase kycCase, List<KycCheck> checks) {
            return new CaseView(kycCase.getId(), kycCase.getBorrowerId(), kycCase.getStatus(),
                    checks.stream().map(CheckView::of).toList());
        }
    }
}
