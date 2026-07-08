package com.navix.loan.service;

import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    private final ApplicationEventRepository eventRepo;
    private final LoanApplicationRepository applicationRepo;

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
        logReverify(appId, checks);
    }

    /**
     * Append a queryable {@code REVERIFY} entry to the application audit trail so re-verifications show
     * up in the customer activity timeline (they only mutate the verification row in place otherwise).
     */
    private void logReverify(Long appId, Set<String> checks) {
        LoanApplication app = applicationRepo.findById(appId).orElse(null);
        if (app == null) {
            return;
        }
        CurrentActor actor = ActorContext.get();
        ApplicationEvent event = new ApplicationEvent();
        event.setApplicationId(appId);
        event.setFromStatus(app.getStatus());
        event.setToStatus(app.getStatus());
        event.setActorId(actor != null ? actor.id() : "system");
        event.setActorRole(actor != null ? actor.role() : null);
        event.setAction("REVERIFY");
        event.setNotes("Re-verification required: " + String.join(", ", new TreeSet<>(checks)));
        event.setAt(Instant.now());
        eventRepo.save(event);
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
