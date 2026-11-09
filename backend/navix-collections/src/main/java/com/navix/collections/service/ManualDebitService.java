package com.navix.collections.service;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.loan.DebitDirectory;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Staff-triggered NACH debit. This takes REAL money, so authorisation is an ALLOWLIST (DSA and every
 * other role are refused by omission, per CLAUDE.md section 7), the case-assignment rule is reused from
 * {@link CollectionsService#assertCaseAccessible}, and the feature flag defaults OFF. Every money guard
 * lives behind {@link DebitDirectory}. Deliberately NOT {@code @Transactional}: the debit call must sit
 * between its own commits.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManualDebitService {

    static final String FLAG = "digio-debit";
    private static final Set<String> ALLOWED = Set.of("COLLECTION_HEAD", "COLLECTION_EXECUTIVE", "ADMIN");
    /** The cross-book refund register is not case-scoped, so an executive does not get it. */
    private static final Set<String> REGISTER_ALLOWED = Set.of("COLLECTION_HEAD", "ADMIN");

    private final CollectionCaseRepository caseRepository;
    private final CollectionsService collectionsService;
    private final DebitDirectory debits;
    private final FeatureFlagService featureFlags;

    public DebitDirectory.Result debit(Long loanId, Long amountPaise) {
        CurrentActor actor = ActorContext.get();
        String actorId = actor == null ? null : actor.id();
        try {
            return attempt(actor, loanId, amountPaise);
        } catch (BusinessException e) {
            // Every denial is recorded BEFORE it propagates: who tried to use this on which loan, and why not.
            log.warn("Manual debit denied loanId={} actor={} code={}", loanId, actorId, e.getCode());
            throw e;
        }
    }

    /**
     * Presentation history for one loan. Same allowlist and case-assignment rule as {@link #debit}, but
     * deliberately NOT gated on the feature flag: after the flag is switched off, the debits it already
     * presented still have to be auditable — the outcome of real money is not a feature you turn off.
     */
    public java.util.List<DebitDirectory.DebitRecord> history(Long loanId) {
        String role = requireCollectionsStaff();
        Optional<CollectionCase> c = caseRepository.findByLoanId(loanId);
        if (c.isPresent()) {
            collectionsService.assertCaseAccessible(c.get().getId()); // CASE_NOT_ASSIGNED for someone else's case
        } else if (!REGISTER_ALLOWED.contains(role)) {
            // Fails CLOSED for an executive. The scheduled sweep can present a debit on a loan that has no
            // case, so a head or ADMIN must still be able to read that history — but with no case there is
            // no assignment to check, and letting an executive through would scope them to nothing at all.
            throw new BusinessException("CASE_NOT_FOUND", "Open a collection case for this loan first");
        }
        return debits.history(loanId);
    }

    /**
     * The refund register — money taken from borrowers who had already repaid. Not case-scoped (it is a
     * cross-book finance view) so it is COLLECTION_HEAD and ADMIN only; an executive sees overpayments on
     * their own cases through {@link #history}.
     */
    public java.util.List<DebitDirectory.DebitRecord> overpayments() {
        CurrentActor actor = ActorContext.get();
        String role = actor == null ? null : actor.role();
        if (role == null || !REGISTER_ALLOWED.contains(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role COLLECTION_HEAD or ADMIN");
        }
        return debits.overpayments();
    }

    /**
     * "Check status" on one debit: re-read its outcome from the provider. Same allowlist and case rule as
     * {@link #history}; not flag-gated, because settling money already in flight is not optional.
     */
    public DebitDirectory.DebitRecord reconcile(Long loanId, Long debitId) {
        requireCaseAccess(loanId);
        return debits.reconcile(loanId, debitId);
    }

    /**
     * Cancel one in-flight debit. Whoever may present a debit on this case may withdraw it; not flag-gated,
     * since stopping money is exactly what must still work with the feature switched off.
     */
    public DebitDirectory.DebitRecord cancel(Long loanId, Long debitId) {
        requireCaseAccess(loanId);
        CurrentActor actor = ActorContext.get();
        String label = actor == null ? "staff" : actor.name() + " (" + actor.role() + ", id " + actor.id() + ")";
        log.info("Debit cancel requested loanId={} debitId={} by={}", loanId, debitId, label);
        return debits.cancel(loanId, debitId, label);
    }

    private void requireCaseAccess(Long loanId) {
        String role = requireCollectionsStaff();
        Optional<CollectionCase> c = caseRepository.findByLoanId(loanId);
        if (c.isPresent()) {
            collectionsService.assertCaseAccessible(c.get().getId());
        } else if (!REGISTER_ALLOWED.contains(role)) {
            throw new BusinessException("CASE_NOT_FOUND", "Open a collection case for this loan first");
        }
    }

    /** The caller's real JWT role, once it is known to be allowed here. */
    private String requireCollectionsStaff() {
        CurrentActor actor = ActorContext.get();
        String role = actor == null ? null : actor.role();
        if (role == null || !ALLOWED.contains(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role COLLECTION_HEAD, COLLECTION_EXECUTIVE or ADMIN");
        }
        return role;
    }

    private DebitDirectory.Result attempt(CurrentActor actor, Long loanId, Long amountPaise) {
        String role = actor == null ? null : actor.role();
        if (role == null || !ALLOWED.contains(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role COLLECTION_HEAD, COLLECTION_EXECUTIVE or ADMIN");
        }
        if (!featureFlags.isEnabled(FLAG, false)) {
            throw new BusinessException("DEBIT_DISABLED", "Debits are not enabled");
        }
        CollectionCase c = caseRepository.findByLoanId(loanId)
                .orElseThrow(() -> new BusinessException("CASE_NOT_FOUND", "Open a collection case for this loan first"));
        collectionsService.assertCaseAccessible(c.getId()); // CASE_NOT_ASSIGNED for an executive on someone else's case

        Long actorId;
        try {
            actorId = Long.valueOf(actor.id());
        } catch (NumberFormatException e) {
            throw new BusinessException("FORBIDDEN_ROLE", "A staff identity is required");
        }
        // The debit row stores actor + MANUAL trigger + amount once created (see DebitServiceTest).
        log.info("Manual debit requested loanId={} actor={} role={} actingRole={} amountPaise={} trigger=MANUAL",
                loanId, actorId, role, actor.actingRole(), amountPaise);
        return debits.presentForCase(loanId, amountPaise, actorId);
    }
}
