package com.navix.loan.service;

import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.DsaDtos.DsaLeadStatus;
import com.navix.loan.entity.Lead;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** PANs per attribution lookup. Well under the 65535 bind-parameter ceiling. */
    private static final int PAN_LOOKUP_CHUNK = 1000;

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

    /**
     * {@link #attributedApplication} for a whole list, in ONE query instead of one per lead.
     *
     * <p>Necessary, not an optimisation. The per-lead form runs
     * {@code findApplicationsAfterByPan} on every call, and the lists this now feeds are large:
     * a bulk import lands tens of thousands of leads under a single uploader, and
     * {@code LeadService.list()} is unpaged. Resolving {@code CONFIRMED} row-by-row over that would
     * be one round trip per lead.
     *
     * <p>Pinning is identical to the per-lead form — the earliest application created strictly after
     * that lead — it is just done in memory over the candidates, because each lead carries its own
     * cutoff and a single SQL predicate cannot express all of them at once.
     *
     * @return lead id → attribution, with an entry for EVERY input lead (never-applied included).
     */
    @Transactional(readOnly = true)
    public Map<Long, AttributedApplication> attributedApplications(List<Lead> leads) {
        Map<Long, AttributedApplication> out = new HashMap<>();
        Set<String> pans = new LinkedHashSet<>();
        for (Lead lead : leads) {
            out.put(lead.getId(), AttributedApplication.notApplied());
            if (lead.getPan() != null && !lead.getPan().isBlank() && lead.getCreatedAt() != null) {
                pans.add(lead.getPan().trim().toUpperCase(Locale.ROOT));
            }
        }
        if (pans.isEmpty()) {
            return out;
        }

        // Chunked: LeadService.list() is unpaged, so `pans` can be the whole lead table after a bulk
        // import, and one IN (...) that size exceeds PostgreSQL's 65535 bind-parameter ceiling
        // outright — the same wall the lead import hit and chunks around.
        Map<String, List<LoanApplication>> byPan = new HashMap<>();
        List<String> panList = new ArrayList<>(pans);
        for (int start = 0; start < panList.size(); start += PAN_LOOKUP_CHUNK) {
            List<String> chunk = panList.subList(
                    start, Math.min(start + PAN_LOOKUP_CHUNK, panList.size()));
            for (Object[] row : customerProfileRepository.findApplicationsByPanIn(chunk)) {
                String pan = (String) row[0];
                byPan.computeIfAbsent(pan == null ? "" : pan.trim().toUpperCase(Locale.ROOT),
                        ignored -> new ArrayList<>()).add((LoanApplication) row[1]);
            }
        }

        for (Lead lead : leads) {
            if (lead.getPan() == null || lead.getPan().isBlank() || lead.getCreatedAt() == null) {
                continue;
            }
            List<LoanApplication> candidates =
                    byPan.get(lead.getPan().trim().toUpperCase(Locale.ROOT));
            if (candidates == null) {
                continue;
            }
            // The query returns them oldest-first, so the first one past this lead's own createdAt
            // is the pinned application — the same rule, and the same result, as the per-lead query.
            for (LoanApplication a : candidates) {
                if (a.getCreatedAt() != null && a.getCreatedAt().isAfter(lead.getCreatedAt())) {
                    out.put(lead.getId(), new AttributedApplication(a, mapStatus(a.getStatus())));
                    break;
                }
            }
        }
        return out;
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
