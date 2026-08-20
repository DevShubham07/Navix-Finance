package com.navix.loan.service;

import com.navix.loan.entity.CustomerPennyDropLock;
import com.navix.loan.entity.PennyDropAttempt;
import com.navix.loan.repository.CustomerPennyDropLockRepository;
import com.navix.loan.repository.PennyDropAttemptRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The penny-drop abuse control (V46; revamp.md decision 40): 3 failures per borrower earn a 12-hour
 * lock, and a success clears the count.
 *
 * <p>A bean of its own, and every write is {@code REQUIRES_NEW}, because a failed attempt is
 * <b>rejected</b> — {@code OfferService.confirmDisbursalAccount} throws, which rolls its transaction
 * back. Recording the strike inside that transaction meant the strike died with it and the counter
 * never moved, so the lock could never fire. The attempt log has to outlive the rejection that
 * caused it, and self-invocation would not have gone through the proxy.
 */
@Service
@RequiredArgsConstructor
public class PennyDropGuard {

    public static final int STRIKES = 3;
    public static final Duration LOCK = Duration.ofHours(12);

    private final PennyDropAttemptRepository attemptRepo;
    private final CustomerPennyDropLockRepository lockRepo;

    /** The live lock on this customer, if any. Absent when there is none or it has expired. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Instant> lockedUntil(Long customerId) {
        return lockRepo.findByCustomerId(customerId)
                .map(CustomerPennyDropLock::getLockedUntil)
                .filter(until -> until.isAfter(Instant.now()));
    }

    /** Attempts left before a lock. Failures count back to the last success or the previous lock. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public int remainingAttempts(Long customerId) {
        if (lockedUntil(customerId).isPresent()) {
            return 0;
        }
        return Math.max(0, STRIKES - failuresSinceReset(customerId));
    }

    /**
     * Log one attempt and apply the rule. Commits independently of the caller, so a rejected attempt
     * still counts.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long customerId, Long applicationId, String account, String ifsc,
                       boolean succeeded, String failureReason) {
        PennyDropAttempt attempt = new PennyDropAttempt();
        attempt.setCustomerId(customerId);
        attempt.setApplicationId(applicationId);
        attempt.setAccountNumber(account);
        attempt.setIfsc(ifsc);
        attempt.setSucceeded(succeeded);
        attempt.setFailureReason(failureReason);
        // Set explicitly: failuresSinceReset compares it below, in this same transaction.
        attempt.setCreatedAt(Instant.now());
        attemptRepo.save(attempt);

        if (succeeded) {
            lockRepo.findByCustomerId(customerId).ifPresent(lockRepo::delete);
            return;
        }
        if (failuresSinceReset(customerId) >= STRIKES) {
            CustomerPennyDropLock lock = lockRepo.findByCustomerId(customerId)
                    .orElseGet(CustomerPennyDropLock::new);
            lock.setCustomerId(customerId);
            lock.setLockedUntil(Instant.now().plus(LOCK));
            lock.setFailures(STRIKES);
            lockRepo.save(lock);
        }
    }

    /**
     * Drop the lock outright. Used by the staff manual override on PENNY_DROP: a reviewer who has
     * satisfied themselves the account is genuine (the usual cause is a bank holding an abbreviated
     * name) must be able to let the borrower through, and leaving the 12-hour lock standing would
     * mean the override changed nothing until it expired.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearLock(Long customerId) {
        lockRepo.findByCustomerId(customerId).ifPresent(lockRepo::delete);
    }

    private int failuresSinceReset(Long customerId) {
        Instant lockedAt = lockRepo.findByCustomerId(customerId)
                .map(CustomerPennyDropLock::getLockedUntil)
                .map(until -> until.minus(LOCK))
                .orElse(null);
        int failures = 0;
        for (PennyDropAttempt a : attemptRepo.findByCustomerIdOrderByIdDesc(customerId)) {
            if (Boolean.TRUE.equals(a.getSucceeded())) {
                break; // a success wipes the slate
            }
            if (lockedAt != null && a.getCreatedAt() != null && !a.getCreatedAt().isAfter(lockedAt)) {
                break; // already paid for by the previous lock
            }
            failures++;
        }
        return failures;
    }
}
