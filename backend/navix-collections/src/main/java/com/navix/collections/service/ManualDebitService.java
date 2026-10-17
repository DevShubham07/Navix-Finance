package com.navix.collections.service;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.loan.DebitDirectory;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
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
