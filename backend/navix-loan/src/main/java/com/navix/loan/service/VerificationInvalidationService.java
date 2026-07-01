package com.navix.loan.service;

import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single seam for <b>invalidating</b> verification when the data behind a check changes (Phase 4).
 * When a borrower (or admin) edits a verification-linked profile field — address, bank, salary,
 * employer/email — the corresponding {@code application_verification} row is reset to {@code PENDING}
 * and the denormalized {@code customer_profile} flag is cleared, so the check must be re-run / re-cleared
 * before the application can proceed. Field → check mapping is centralised here:
 *
 * <ul>
 *   <li>{@code address}            → {@code ADDRESS}</li>
 *   <li>{@code salaryBank}         → {@code PENNY_DROP}</li>
 *   <li>{@code monthlySalaryPaise} → {@code SALARY} (caller also recomputes eligibility)</li>
 *   <li>{@code employer} / {@code employmentStatus} / {@code email} → {@code EMAIL} (EPFO establishment match)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class VerificationInvalidationService {

    private static final String PENDING = "PENDING";
    private static final String RESET_MESSAGE = "Reset for re-verification after a profile edit";

    /** Profile fields → the verification check each invalidates. */
    static final Map<String, String> FIELD_TO_CHECK = Map.of(
            "address", "ADDRESS",
            "salaryBank", "PENNY_DROP",
            "monthlySalaryPaise", "SALARY",
            "employer", "EMAIL",
            "employmentStatus", "EMAIL",
            "email", "EMAIL");

    private final ApplicationVerificationRepository verificationRepo;
    private final CustomerProfileRepository profileRepo;

    /**
     * Reset the verification(s) tied to the given changed profile fields back to {@code PENDING} and
     * clear the matching profile flags. {@code changedFields} are profile property names (see
     * {@link #FIELD_TO_CHECK}); unknown / non-verification fields are ignored. No-op when empty.
     */
    @Transactional
    public void invalidateForFields(Long appId, Set<String> changedFields) {
        if (changedFields == null || changedFields.isEmpty()) {
            return;
        }
        java.util.Set<String> checks = new java.util.HashSet<>();
        for (String f : changedFields) {
            String c = FIELD_TO_CHECK.get(f);
            if (c != null) {
                checks.add(c);
            }
        }
        if (checks.isEmpty()) {
            return;
        }
        for (String check : checks) {
            verificationRepo.findByApplicationIdAndCheckType(appId, check).ifPresent(v -> {
                v.setStatus(PENDING);
                v.setMessage(RESET_MESSAGE);
                verificationRepo.save(v);
            });
        }
        profileRepo.findByApplicationId(appId).ifPresent(p -> {
            clearFlags(p, checks);
            profileRepo.save(p);
        });
    }

    private static void clearFlags(CustomerProfile p, Set<String> checks) {
        if (checks.contains("ADDRESS")) {
            p.setAddressVerified(false);
        }
        if (checks.contains("PENNY_DROP")) {
            p.setPennyDropVerified(false);
        }
        if (checks.contains("EMAIL")) {
            p.setEmailVerified(false);
        }
    }
}
