package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlag;
import com.navix.common.featureflag.FeatureFlagRepository;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The close-applications switch: while {@value #FLAG} is on, {@code ApplicationFlowService} refuses
 * every new application and reborrow with {@code APPLICATIONS_CLOSED}. Files already in flight carry
 * on; nothing is cancelled and nobody is notified. Upserted like the onboarding switch, so no migration.
 */
@Service
@RequiredArgsConstructor
public class IntakeSwitchService {

    public static final String FLAG = "applications-closed";

    private final FeatureFlagRepository featureFlagRepository;

    /** Whether applications are closed, and who last flipped it (null if never touched). */
    public record ClosedStatus(boolean closed, String updatedBy, Instant updatedAt) {
    }

    @Transactional(readOnly = true)
    public ClosedStatus status() {
        requireAdmin();
        return featureFlagRepository.findByFlagKey(FLAG).map(IntakeSwitchService::statusOf)
                .orElseGet(() -> new ClosedStatus(false, null, null));
    }

    @Transactional
    public ClosedStatus setClosed(boolean closed) {
        requireAdmin();
        FeatureFlag flag = featureFlagRepository.findByFlagKey(FLAG).orElseGet(() -> {
            FeatureFlag f = new FeatureFlag();
            f.setFlagKey(FLAG);
            f.setDescription("Close all new applications and reloans. ON: createDraft and reborrow refuse "
                    + "APPLICATIONS_CLOSED; in-progress files and active loans are untouched.");
            return f;
        });
        flag.setEnabled(closed);
        return statusOf(featureFlagRepository.save(flag));
    }

    private static ClosedStatus statusOf(FeatureFlag f) {
        return new ClosedStatus(f.isEnabled(), f.getUpdatedBy(), f.getUpdatedAt());
    }

    private static void requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
    }
}
