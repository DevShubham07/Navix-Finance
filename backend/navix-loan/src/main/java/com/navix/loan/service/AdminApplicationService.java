package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.AdminApplicationDtos.AdminApplicationView;
import com.navix.loan.dto.AdminApplicationDtos.RejectionView;
import com.navix.loan.entity.ApplicationRejection;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationRejectionRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private static void requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
    }
}
