package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import java.util.Arrays;

/**
 * The credit-team-or-admin role check, extracted out of
 * {@link ApplicationVerificationService#requireCreditTeamOr} so every "nudge one borrower on their
 * own file" action shares one role list instead of each carrying its own copy: it now also backs
 * {@link VerificationOutreachService} (both {@code preview} and {@code share}) and
 * {@link BureauChallengeOutreachService#notifyApplication} (the single-application bureau nudge —
 * deliberately widened from ADMIN-only to match the new feature's audience; the cohort sweeps stay
 * ADMIN-only via their own {@code requireAdmin()}).
 */
final class CreditTeamGuard {

    private CreditTeamGuard() {
    }

    static void requireCreditTeamOr(String what, String... extraRoles) {
        String role = ActorContext.get().role();
        boolean core = "CREDIT_EXECUTIVE".equals(role) || "CREDIT_HEAD".equals(role) || "ADMIN".equals(role);
        boolean extra = extraRoles != null && Arrays.asList(extraRoles).contains(role);
        if (!core && !extra) {
            throw new BusinessException("FORBIDDEN_ROLE",
                    what + " requires CREDIT_EXECUTIVE or CREDIT_HEAD");
        }
    }
}
