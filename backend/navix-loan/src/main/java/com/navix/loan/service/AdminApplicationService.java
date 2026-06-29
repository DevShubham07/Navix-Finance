package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.AdminApplicationDtos.AdminApplicationView;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicantProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    private final ApplicantProfileRepository profileRepository;
    private final ApplicationVerificationService verification;

    /** Every application with full detail + completeness, newest first. ADMIN only. */
    @Transactional(readOnly = true)
    public List<AdminApplicationView> listAll() {
        requireAdmin();
        List<LoanApplication> apps = applicationRepository.findAll();
        if (apps.isEmpty()) {
            return List.of();
        }
        Map<Long, ApplicantProfile> byApp = profileRepository
                .findByApplicationIdIn(apps.stream().map(LoanApplication::getId).toList()).stream()
                .collect(Collectors.toMap(ApplicantProfile::getApplicationId, p -> p, (a, b) -> a));
        int required = ApplicationVerificationService.requiredCount();
        return apps.stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed())
                .map(a -> {
                    ApplicantProfile p = byApp.get(a.getId());
                    int completed = verification.requiredPassedCount(a.getId());
                    boolean agreement = p != null && Boolean.TRUE.equals(p.getAgreementAccepted());
                    boolean complete = completed >= required && agreement;
                    return AdminApplicationView.of(a, p, completed, required, agreement, complete);
                })
                .toList();
    }

    private static void requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
    }
}
