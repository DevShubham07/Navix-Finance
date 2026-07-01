package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.loan.entity.BorrowerPreferences;
import com.navix.loan.repository.BorrowerPreferencesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Borrower-scoped notification preferences (Phase 2.2). The caller is resolved from the JWT
 * ({@code ActorContext} subject = customerId); a borrower may only read/write their OWN row. Replaces
 * the old browser-only settings — the notification engine now honours these server-persisted opt-outs.
 */
@Service
@RequiredArgsConstructor
public class BorrowerPreferencesService {

    /** Borrower-facing view/update payload (all channels default on). */
    public record PreferencesView(boolean emailOptIn, boolean smsOptIn, boolean offersOptIn) {
        public static PreferencesView of(BorrowerPreferences p) {
            return new PreferencesView(p.isEmailOptIn(), p.isSmsOptIn(), p.isOffersOptIn());
        }

        /** All-on default for a borrower with no saved row yet. */
        public static PreferencesView defaults() {
            return new PreferencesView(true, true, true);
        }
    }

    private final BorrowerPreferencesRepository repository;

    /** The calling borrower's preferences (defaults when none saved yet). */
    @Transactional(readOnly = true)
    public PreferencesView getMine() {
        Long customerId = currentBorrower();
        return repository.findByCustomerId(customerId)
                .map(PreferencesView::of)
                .orElseGet(PreferencesView::defaults);
    }

    /** Upsert the calling borrower's preferences. */
    @Transactional
    public PreferencesView updateMine(PreferencesView req) {
        Long customerId = currentBorrower();
        BorrowerPreferences p = repository.findByCustomerId(customerId)
                .orElseGet(BorrowerPreferences::new);
        p.setCustomerId(customerId);
        p.setEmailOptIn(req.emailOptIn());
        p.setSmsOptIn(req.smsOptIn());
        p.setOffersOptIn(req.offersOptIn());
        return PreferencesView.of(repository.save(p));
    }

    private static Long currentBorrower() {
        var actor = ActorContext.get();
        if (!"BORROWER".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Preferences are borrower-scoped");
        }
        try {
            return Long.valueOf(actor.id());
        } catch (NumberFormatException e) {
            throw new BusinessException("FORBIDDEN", "Could not resolve borrower identity");
        }
    }
}
