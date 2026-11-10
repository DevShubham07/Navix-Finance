package com.navix.loan.payu;

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
 * The admin payment-gateway switch (Staff → Settings): flips the {@code payu-repayment} flag that
 * {@link PayuService#enabled()} reads. OFF puts every borrower back on the manual repay screen (UPI / bank
 * details + screenshot proof, verified by the Accountant). Orders already at PayU keep reconciling either way
 * ({@code PayuReconcileScheduler} ignores the flag), so turning it off never strands money in flight.
 *
 * <p>Turning it ON is refused ({@code PAYU_NOT_CONFIGURED}) unless the merchant key/salt are set and the hosts
 * are allowed: {@code ProductionConfigGuard} refuses to boot with the flag on and no credentials, so letting the
 * flag on here would turn the next deploy into an outage.
 */
@Service
@RequiredArgsConstructor
public class PayuSwitchService {

    private final FeatureFlagRepository featureFlagRepository;
    private final PayuProperties props;

    /** {@code enabled} = the flag; {@code configured} = credentials + allowed hosts, i.e. whether ON would work. */
    public record GatewayStatus(boolean enabled, boolean configured, String updatedBy, Instant updatedAt) {
    }

    @Transactional(readOnly = true)
    public GatewayStatus status() {
        requireAdmin();
        return featureFlagRepository.findByFlagKey(PayuService.FLAG).map(this::statusOf)
                .orElseGet(() -> new GatewayStatus(false, configured(), null, null));
    }

    @Transactional
    public GatewayStatus setEnabled(boolean enabled) {
        requireAdmin();
        if (enabled && !configured()) {
            throw new BusinessException("PAYU_NOT_CONFIGURED",
                    "The payment gateway cannot be turned on: its merchant credentials are not set up on this server");
        }
        FeatureFlag flag = featureFlagRepository.findByFlagKey(PayuService.FLAG).orElseGet(() -> {
            FeatureFlag f = new FeatureFlag();
            f.setFlagKey(PayuService.FLAG);
            f.setDescription("PayU Hosted Checkout for loan repayments. OFF: borrowers pay manually "
                    + "(UPI / bank + screenshot). Flipped by ADMIN from Staff → Settings.");
            return f;
        });
        flag.setEnabled(enabled);
        return statusOf(featureFlagRepository.save(flag));
    }

    private boolean configured() {
        return props.credentialsSet() && props.hostsAllowed();
    }

    private GatewayStatus statusOf(FeatureFlag f) {
        return new GatewayStatus(f.isEnabled(), configured(), f.getUpdatedBy(), f.getUpdatedAt());
    }

    private static void requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
    }
}
