package com.navix.loan.service;

import com.navix.common.blocklist.BlocklistKey;
import com.navix.common.security.BorrowerIdentityPort;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Every identifier of one customer the fraud blocklist can match: the login mobile plus, across ALL
 * their applications, each profile's mobile, PAN, Aadhaar and salary account and each application's
 * disbursal account — so an old PAN or a previous payout account still matches after an edit.
 * Shared by the new-application screen ({@code ApplicationFlowService.assertNotBlocked}), "Block
 * customer" / unblock, and the customer page's blocked status.
 */
@Component
@RequiredArgsConstructor
public class CustomerBlocklistKeys {

    private final LoanApplicationRepository applicationRepository;
    private final CustomerProfileRepository profileRepository;
    private final BorrowerIdentityPort borrowerIdentity;

    public List<BlocklistKey> forCustomer(Long customerId) {
        Set<BlocklistKey> keys = new LinkedHashSet<>();
        if (customerId == null) {
            return List.of();
        }
        borrowerIdentity.loginMobile(customerId).ifPresent(m -> keys.add(BlocklistKey.of("PHONE", m)));
        for (LoanApplication app : applicationRepository.findByCustomerId(customerId)) {
            keys.add(BlocklistKey.of("BANK_ACCOUNT", app.getDisbursalAccountNumber()));
            CustomerProfile p = profileRepository.findByApplicationId(app.getId()).orElse(null);
            if (p != null) {
                keys.add(BlocklistKey.of("PHONE", p.getMobile()));
                keys.add(BlocklistKey.of("PAN", p.getPan()));
                keys.add(BlocklistKey.of("AADHAAR_REF", p.getAadhaar()));
                keys.add(BlocklistKey.of("BANK_ACCOUNT", p.getSalaryAccountNumber()));
            }
        }
        keys.remove(null);
        return List.copyOf(keys.stream().filter(Objects::nonNull).toList());
    }
}
