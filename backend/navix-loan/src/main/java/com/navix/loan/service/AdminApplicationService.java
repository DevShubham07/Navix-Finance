package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.AdminApplicationDtos.AdminApplicationView;
import com.navix.loan.dto.AdminApplicationDtos.RejectionView;
import com.navix.loan.dto.TelecallingDtos.TelecallingView;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.ApplicationRejection;
import com.navix.loan.entity.CustomerOwner;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.ApplicationRejectionRepository;
import com.navix.loan.repository.CustomerOwnerRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN-only register of EVERY application — complete and incomplete (DRAFT / partially filled) —
 * with full KYC detail and an onboarding-completeness summary, newest first. Backs the admin
 * "All applications" page + its CSV / PDF export.
 *
 * <p>Profiles are batch-loaded (one query) and joined per application; completeness reuses
 * {@link ApplicationVerificationService} (required steps PASS/REVIEW + agreement accepted).
 */
@Service
@RequiredArgsConstructor
public class AdminApplicationService {

    private final LoanApplicationRepository applicationRepository;
    private final CustomerProfileRepository profileRepository;
    private final ApplicationVerificationService verification;
    private final ApplicationRejectionRepository rejectionRepository;
    private final ApplicationEventRepository eventRepository;
    private final CustomerOwnerRepository ownerRepository;

    /** Statuses that mean the application has reached (or passed) SANCTIONED — everything else is
     *  "still pre-sanction" and belongs in the telecalling queue (work item 10). */
    private static final Set<ApplicationStatus> REACHED_SANCTIONED = EnumSet.of(
            ApplicationStatus.SANCTIONED, ApplicationStatus.DISBURSEMENT_PENDING,
            ApplicationStatus.ACCOUNTANT_PENDING, ApplicationStatus.DISBURSEMENT_FAILED,
            ApplicationStatus.DISBURSED, ApplicationStatus.ACTIVE, ApplicationStatus.OVERDUE,
            ApplicationStatus.DEFAULTED, ApplicationStatus.CLOSED, ApplicationStatus.WRITTEN_OFF);

    /** Every application with full detail + completeness, newest first. ADMIN only. */
    @Transactional(readOnly = true)
    public List<AdminApplicationView> listAll() {
        requireAdmin();
        List<LoanApplication> apps = applicationRepository.findAll();
        if (apps.isEmpty()) {
            return List.of();
        }
        Map<Long, CustomerProfile> byApp = profileRepository
                .findByApplicationIdIn(apps.stream().map(LoanApplication::getId).toList()).stream()
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
        int required = ApplicationVerificationService.requiredCount();
        return apps.stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed())
                .map(a -> {
                    CustomerProfile p = byApp.get(a.getId());
                    int completed = verification.requiredPassedCount(a.getId());
                    // The retired AGREEMENT step is now the screen-1 T&C acceptance (revamp.md decision 25).
                    boolean agreement = p != null && p.getTermsAcceptedAt() != null;
                    boolean complete = completed >= required && agreement;
                    return AdminApplicationView.of(a, p, completed, required, agreement, complete);
                })
                .toList();
    }

    /** The rejection register — every automatic and manual rejection, newest first. ADMIN only. */
    @Transactional(readOnly = true)
    public List<RejectionView> listRejections(String reasonCode) {
        requireAdmin();
        List<ApplicationRejection> rows = reasonCode == null || reasonCode.isBlank()
                ? rejectionRepository.findAllByOrderByIdDesc()
                : rejectionRepository.findByReasonCodeOrderByIdDesc(reasonCode);
        Map<Long, CustomerProfile> byApp = profileRepository.findByApplicationIdIn(
                        rows.stream().map(ApplicationRejection::getApplicationId).filter(Objects::nonNull).toList())
                .stream()
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
        return rows.stream()
                .map(r -> RejectionView.of(r, r.getApplicationId() == null ? null : byApp.get(r.getApplicationId())))
                .toList();
    }

    /**
     * The telecaller queue (work item 10): every application whose status has not yet reached
     * SANCTIONED, enriched for a follow-up call — completeness reuses the same computation as
     * {@link #listAll()} rather than re-deriving it, plus the {@code customer_owner} assignee and a
     * staleness figure from the latest {@code application_event} (falling back to {@code createdAt}).
     * TELECALLER or ADMIN only.
     */
    @Transactional(readOnly = true)
    public List<TelecallingView> listForTelecalling() {
        requireTelecallingRole();
        List<LoanApplication> apps = applicationRepository.findAll().stream()
                .filter(a -> !REACHED_SANCTIONED.contains(a.getStatus()))
                .toList();
        if (apps.isEmpty()) {
            return List.of();
        }
        List<Long> appIds = apps.stream().map(LoanApplication::getId).toList();
        Map<Long, CustomerProfile> byApp = profileRepository.findByApplicationIdIn(appIds).stream()
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
        // First event per application id wins (query is ordered newest-first).
        Map<Long, Instant> latestActivity = eventRepository.findByApplicationIdInOrderByAtDesc(appIds).stream()
                .collect(Collectors.toMap(ApplicationEvent::getApplicationId, ApplicationEvent::getAt, (a, b) -> a));
        List<Long> customerIds = apps.stream().map(LoanApplication::getCustomerId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Long> ownerByCustomer = ownerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(CustomerOwner::getCustomerId, CustomerOwner::getOwnerStaffId));
        int required = ApplicationVerificationService.requiredCount();
        Instant now = Instant.now();
        return apps.stream()
                .map(a -> {
                    CustomerProfile p = byApp.get(a.getId());
                    int completed = verification.requiredPassedCount(a.getId());
                    // LoanApplication carries no createdAt of its own — the CREATE application_event
                    // is the closest equivalent, so an application with any event history at all
                    // always resolves through latestActivity; only a row with truly no events (should
                    // not happen in practice) falls back to "just seen" rather than overstating staleness.
                    Instant last = latestActivity.get(a.getId());
                    long staleDays = last == null ? 0L : Math.max(0L, ChronoUnit.DAYS.between(last, now));
                    return new TelecallingView(a.getId(), a.getCustomerId(), a.getStatus(),
                            p != null ? p.getFullName() : null,
                            p != null ? p.getMobile() : null,
                            p != null ? p.getEmail() : null,
                            p != null ? p.getPan() : null,
                            completed, required, ownerByCustomer.get(a.getCustomerId()), staleDays);
                })
                .sorted(Comparator.comparingLong(TelecallingView::staleDays).reversed())
                .toList();
    }

    private static void requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
    }

    private static void requireTelecallingRole() {
        CurrentActor actor = ActorContext.get();
        String role = actor == null ? null : actor.role();
        if (!"TELECALLER".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role TELECALLER or ADMIN");
        }
    }
}
