package com.navix.collections.service;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.common.notification.event.ApplicationTransitionedEvent;
import com.navix.common.staff.StaffDirectory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Puts every freshly disbursed loan straight onto one collections executive's desk, so the UPCOMING
 * bucket never shows a new loan as "Unallocated". The officer is {@code navix.collections.auto-assign-officer-id}
 * (SSM {@code /navix/<env>/navix/collections/auto-assign-officer-id}); unset, or not an ACTIVE
 * COLLECTION_EXECUTIVE, and nothing happens -- the Head assigns by hand as before.
 *
 * <p>Deliberately not {@link CollectionsService#openCase}: that flips a past-due loan to IN_COLLECTIONS and
 * pings the desk with "New collections case". A loan disbursed a minute ago is neither, so this only writes
 * the case row. The Head can still reassign it.
 */
@Slf4j
@Component
public class CollectionAutoAssigner {

    private final CollectionCaseRepository caseRepository;
    private final StaffDirectory staffDirectory;
    private final Long officerId;

    public CollectionAutoAssigner(CollectionCaseRepository caseRepository, StaffDirectory staffDirectory,
                                  @Value("${navix.collections.auto-assign-officer-id:#{null}}") Long officerId) {
        this.caseRepository = caseRepository;
        this.staffDirectory = staffDirectory;
        this.officerId = officerId;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTransition(ApplicationTransitionedEvent e) {
        if (officerId == null || !"ACTIVE".equals(e.toStatus()) || e.loanId() == null) {
            return;
        }
        try {
            if (caseRepository.findFirstByLoanIdOrderByCreatedAtDesc(e.loanId()).isPresent()) {
                return;
            }
            if (!staffDirectory.isActiveWithRole(officerId, "COLLECTION_EXECUTIVE")) {
                log.warn("collections auto-assign skipped for loan {}: officer {} is not an active executive",
                        e.loanId(), officerId);
                return;
            }
            CollectionCase c = new CollectionCase();
            c.setLoanId(e.loanId());
            c.setAssignedOfficerId(officerId);
            caseRepository.save(c);
            log.info("collections auto-assign: loan {} -> officer {}", e.loanId(), officerId);
        } catch (RuntimeException ex) {
            // The disbursal already committed; a missed auto-assign just leaves the loan for the Head.
            log.error("collections auto-assign failed for loan {}", e.loanId(), ex);
        }
    }
}
