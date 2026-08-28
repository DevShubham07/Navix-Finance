package com.navix.loan.service;

import com.navix.common.loan.ApplicationActorDirectory;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads "who handled this file" out of the {@code application_event} trail — the credit executive
 * who decided it and the staffer who released the money — for a whole page of applications at a time.
 *
 * <p>Nothing new is recorded: the trail has always held the actor of every transition, it was simply
 * never surfaced on a list row. Deliberately distinct from {@code ApplicationView.assignedExecutiveName},
 * which is who a file is assigned <em>to</em>; on a reassigned or Head-decided file those are
 * different people, and the register is meant to answer who actually made the call.
 */
@Service
@RequiredArgsConstructor
public class ApplicationActorResolver implements ApplicationActorDirectory {

    /**
     * The terminal credit call: sanction, or the reject that closes the file (with its 30-day
     * cooling-off). {@code EXEC_*}/{@code HEAD_*} are the pre-V45 two-desk labels — nothing has
     * emitted them since, but historical rows still carry them and a register reading old files
     * should still name the decider.
     */
    private static final Set<String> CREDIT_DECISION_ACTIONS = Set.of(
            "SANCTION", "REJECT_LEAD",
            "EXEC_APPROVE", "EXEC_REJECT", "HEAD_APPROVE", "HEAD_REJECT");

    /**
     * The release of the money. {@code VALIDATE_SUCCESS} is what the Disbursement Head's accept
     * emits today ({@code ApplicationFlowService} → DISBURSED); {@code DISB_ACCEPT} is the retired
     * label kept for historical rows. {@code ADMIN_FORCE_DISBURSE} is deliberately NOT here — it
     * pushes a file into the disbursement queue, it does not transfer anything.
     */
    private static final Set<String> DISBURSEMENT_ACTIONS = Set.of("VALIDATE_SUCCESS", "DISB_ACCEPT");

    private final ApplicationEventRepository eventRepository;
    private final LoanApplicationRepository applicationRepository;
    private final StaffDirectory staffDirectory;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, HandledBy> byApplicationId(Collection<Long> applicationIds) {
        if (applicationIds == null || applicationIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = applicationIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        // One name cache across both passes: a Credit Head who also forced a disbursal is resolved once.
        Map<Long, String> names = new HashMap<>();
        Map<Long, Actor> deciders = latestActorPerApplication(ids, CREDIT_DECISION_ACTIONS, names);
        Map<Long, Actor> disbursers = latestActorPerApplication(ids, DISBURSEMENT_ACTIONS, names);

        Map<Long, HandledBy> result = new LinkedHashMap<>();
        for (Long id : ids) {
            Actor decided = deciders.get(id);
            Actor disbursed = disbursers.get(id);
            if (decided == null && disbursed == null) {
                continue;
            }
            result.put(id, new HandledBy(
                    decided == null ? null : decided.id(), decided == null ? null : decided.name(),
                    disbursed == null ? null : disbursed.id(), disbursed == null ? null : disbursed.name()));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, HandledBy> byLoanId(Collection<Long> loanIds) {
        if (loanIds == null || loanIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = loanIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> applicationIdByLoanId = new LinkedHashMap<>();
        for (LoanApplication app : applicationRepository.findByLoanIdIn(ids)) {
            applicationIdByLoanId.put(app.getLoanId(), app.getId());
        }
        Map<Long, HandledBy> byApplication = byApplicationId(applicationIdByLoanId.values());

        Map<Long, HandledBy> result = new LinkedHashMap<>();
        applicationIdByLoanId.forEach((loanId, applicationId) -> {
            HandledBy handled = byApplication.get(applicationId);
            if (handled != null) {
                result.put(loanId, handled);
            }
        });
        return result;
    }

    /**
     * Newest-first over one page of applications, keeping the first hit per application — the same
     * idiom {@code ApplicationController.latestEventAtByAppId} uses for stage-entry timestamps.
     */
    private Map<Long, Actor> latestActorPerApplication(List<Long> applicationIds, Set<String> actions,
                                                       Map<Long, String> nameCache) {
        Map<Long, Actor> latest = new LinkedHashMap<>();
        for (ApplicationEvent event :
                eventRepository.findByApplicationIdInAndActionInOrderByAtDesc(applicationIds, actions)) {
            latest.computeIfAbsent(event.getApplicationId(), id -> toActor(event, nameCache));
        }
        return latest;
    }

    /**
     * An actor id that no longer resolves to a staff row (a deactivated staffer, a system actor)
     * yields a null name rather than a placeholder — the register renders a blank cell, and a
     * missing name never breaks a page.
     */
    private Actor toActor(ApplicationEvent event, Map<Long, String> nameCache) {
        Long staffId;
        try {
            staffId = Long.valueOf(event.getActorId());
        } catch (RuntimeException e) {
            return new Actor(null, null);
        }
        String name = nameCache.computeIfAbsent(staffId,
                id -> staffDirectory.findStaff(id).map(StaffSummary::name).orElse(null));
        return new Actor(staffId, name);
    }

    private record Actor(Long id, String name) {
    }
}
