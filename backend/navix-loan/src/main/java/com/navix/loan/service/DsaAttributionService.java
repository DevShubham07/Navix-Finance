package com.navix.loan.service;

import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.DsaDtos.DsaLeadStatus;
import com.navix.loan.entity.Lead;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a DSA lead's attributed application — the SINGLE earliest {@link LoanApplication}
 * created after the lead, for a customer whose PAN matches the lead's PAN — and maps its
 * {@link ApplicationStatus} to a coarse, borrower-safe {@link DsaLeadStatus}. Shared by
 * {@code DsaService} (building {@code DsaLeadView}) and {@code DsaCommissionService} (the accrual
 * guard), so both agree on exactly which application a lead is credited against.
 *
 * <p>Pinning is a natural consequence of "earliest after the lead, ascending": once an application
 * exists it can never be un-created and nothing can be inserted with an earlier {@code createdAt}
 * after the fact, so the earliest post-lead application is permanently fixed for the lead's
 * lifetime without needing to persist a separate pin column — a second (repeat-borrow) application
 * by the same PAN is always later and never displaces it.
 */
@Service
@RequiredArgsConstructor
public class DsaAttributionService {

    private final CustomerProfileRepository customerProfileRepository;

    /** One resolved attribution: the pinned application (if any) and its coarse status. */
    public record AttributedApplication(LoanApplication application, DsaLeadStatus status) {

        public static AttributedApplication notApplied() {
            return new AttributedApplication(null, DsaLeadStatus.NOT_APPLIED);
        }
    }

    @Transactional(readOnly = true)
    public AttributedApplication attributedApplication(Lead lead) {
        if (lead.getPan() == null || lead.getPan().isBlank() || lead.getCreatedAt() == null) {
            return AttributedApplication.notApplied();
        }
        String pan = lead.getPan().trim().toUpperCase();
        List<LoanApplication> candidates =
                customerProfileRepository.findApplicationsAfterByPan(pan, lead.getCreatedAt());
        if (candidates.isEmpty()) {
            return AttributedApplication.notApplied();
        }
        LoanApplication earliest = candidates.get(0);
        return new AttributedApplication(earliest, mapStatus(earliest.getStatus()));
    }

    /** Coarse mapping — the DSA learns "declined", never why; handles historical/deprecated states too. */
    private static DsaLeadStatus mapStatus(ApplicationStatus status) {
        if (status == null) {
            return DsaLeadStatus.APPLIED;
        }
        return switch (status) {
            case DRAFT, KYC_PENDING -> DsaLeadStatus.APPLIED;
            case KYC_APPROVED, PRE_APPROVED, REVIEW_PENDING, CREDIT_EXEC_PENDING,
                    CREDIT_EXEC_APPROVED, CREDIT_HEAD_PENDING, CREDIT_HEAD_APPROVED,
                    SANCTIONED, DISBURSEMENT_PENDING, ACCOUNTANT_PENDING, DISBURSEMENT_FAILED ->
                    DsaLeadStatus.IN_PROGRESS;
            // DEFAULTED/WRITTEN_OFF: the money was disbursed (unlike a REJECTED/CANCELLED file that
            // never drew down) — the loan simply never got repaid, so it stays DISBURSED to the DSA
            // rather than reading as "declined". The commission itself is separately voided.
            case DISBURSED, ACTIVE, OVERDUE, DEFAULTED, WRITTEN_OFF -> DsaLeadStatus.DISBURSED;
            case CLOSED -> DsaLeadStatus.REPAID;
            case KYC_REJECTED, REJECTED, CANCELLED -> DsaLeadStatus.DECLINED;
        };
    }

    /** Convenience for callers that only need the resolved application, e.g. admin manual-create. */
    @Transactional(readOnly = true)
    public Optional<LoanApplication> attributedApplicationOnly(Lead lead) {
        return Optional.ofNullable(attributedApplication(lead).application());
    }
}
