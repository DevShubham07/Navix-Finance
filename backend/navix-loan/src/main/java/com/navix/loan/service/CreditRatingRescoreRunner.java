package com.navix.loan.service;

import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.repository.CustomerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Keeps every stored 1–5★ rating on the current {@link CreditRatingCalculator} rules. On each boot it
 * recomputes stars, verdict and summary from the bureau facts already saved on the profile — no
 * bureau pull, nothing billable — and writes only the profiles whose rating actually changed, so a
 * boot with no rule change touches nothing.
 *
 * <p>Runs off the startup thread ({@code @Async}) so a large book never delays readiness, and one
 * unreadable profile is logged and skipped rather than stopping the sweep.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CreditRatingRescoreRunner {

    private final CustomerProfileRepository profileRepo;
    private final CreditBriefService briefService;

    @Value("${navix.credit-rating.rescore-on-boot:true}")
    private boolean enabled;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void rescoreOnBoot() {
        if (!enabled) {
            return;
        }
        int changed = 0;
        int failed = 0;
        var ids = profileRepo.findIdsWithCreditBriefFacts();
        for (Long id : ids) {
            try {
                CustomerProfile profile = profileRepo.findById(id).orElse(null);
                if (profile != null && briefService.rescoreStored(profile)) {
                    changed++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("Credit rating re-score failed for profile {}: {}", id, e.toString());
            }
        }
        log.info("Credit rating re-score: {} stored brief(s) checked, {} updated, {} failed",
                ids.size(), changed, failed);
    }
}
