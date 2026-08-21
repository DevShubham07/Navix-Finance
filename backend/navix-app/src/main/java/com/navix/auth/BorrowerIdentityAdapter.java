package com.navix.auth;

import com.navix.common.security.BorrowerIdentityPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link BorrowerIdentityPort} backed by {@link BorrowerMobileRepository} — the same claim table
 * {@link AuthController#claimCustomerId} maintains at login. Lives in {@code com.navix.auth}
 * specifically so it can call the package-private {@link AuthController#deriveCustomerId} rather
 * than duplicating that hashing logic.
 */
@Component
@RequiredArgsConstructor
class BorrowerIdentityAdapter implements BorrowerIdentityPort {

    private final BorrowerMobileRepository mobileRepository;

    @Override
    public boolean wouldCollideWithAnotherCustomer(String mobile, Long customerId) {
        long derivedId = AuthController.deriveCustomerId(mobile);
        return mobileRepository.findById(derivedId)
                .map(claim -> !claim.getCustomerId().equals(customerId))
                .orElse(false);
    }
}
