package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-staffer decision history, read off the append-only {@code application_event} trail.
 *
 * <p>Scoping (revamp.md decision 32): everyone sees their own decisions; a Head may look at their
 * team's; ADMIN may look at anyone's. Anything else is {@code FORBIDDEN}.
 */
@Service
@RequiredArgsConstructor
public class DecisionHistoryService {

    /** Actions that count as a decision — the rest of the trail is routing/audit noise. */
    private static final Set<String> DECISION_ACTIONS = Set.of(
            "KYC_APPROVE", "KYC_REJECT", "ASSIGN", "SANCTION", "REJECT_LEAD", "MARK_PENDING",
            "EXEC_APPROVE", "EXEC_REJECT", "HEAD_APPROVE", "HEAD_REJECT",
            "DISB_ACCEPT", "DISB_REJECT", "VALIDATE_SUCCESS", "VALIDATE_FAIL", "RETRY", "CANCEL");

    /** Which roles a Head may inspect. Heads only — everyone else sees just themselves. */
    private static final Map<String, Set<String>> TEAM_OF = Map.of(
            "CREDIT_HEAD", Set.of("CREDIT_HEAD", "CREDIT_EXECUTIVE"),
            "COLLECTION_HEAD", Set.of("COLLECTION_HEAD", "COLLECTION_EXECUTIVE"));

    private final ApplicationEventRepository eventRepository;
    private final CustomerProfileRepository profileRepository;
    private final StaffDirectory staffDirectory;

    public record DecisionView(Long applicationId, String customerName, String action, String fromStatus,
                               String toStatus, String notes, Instant at) {
    }

    /** {@code staffId} null → the caller's own history. */
    @Transactional(readOnly = true)
    public List<DecisionView> decisions(Long staffId) {
        String callerId = ActorContext.get().id();
        String target = staffId != null ? String.valueOf(staffId) : callerId;
        if (!target.equals(callerId)) {
            requireCanInspect(staffId);
        }
        return eventRepository.findByActorIdOrderByAtDesc(target).stream()
                .filter(e -> DECISION_ACTIONS.contains(e.getAction()))
                .map(this::view)
                .toList();
    }

    /** Staff whose history the caller may open — for the Head's team switcher. */
    @Transactional(readOnly = true)
    public List<StaffSummary> inspectable() {
        String role = ActorContext.get().role();
        if ("ADMIN".equals(role)) {
            return TEAM_OF.values().stream().flatMap(Set::stream).distinct()
                    .flatMap(r -> staffDirectory.listActive(r).stream())
                    .toList();
        }
        return TEAM_OF.getOrDefault(role, Set.of()).stream()
                .flatMap(r -> staffDirectory.listActive(r).stream())
                .toList();
    }

    private void requireCanInspect(Long staffId) {
        String role = ActorContext.get().role();
        if ("ADMIN".equals(role)) {
            return;
        }
        Set<String> team = TEAM_OF.getOrDefault(role, Set.of());
        boolean inTeam = staffDirectory.findStaff(staffId)
                .map(s -> team.contains(s.role()))
                .orElse(false);
        if (!inTeam) {
            throw new BusinessException("FORBIDDEN", "You can only view your own team's decisions");
        }
    }

    private DecisionView view(ApplicationEvent e) {
        String name = profileRepository.findByApplicationId(e.getApplicationId())
                .map(CustomerProfile::getFullName)
                .orElse(null);
        return new DecisionView(e.getApplicationId(), name, e.getAction(),
                e.getFromStatus() != null ? e.getFromStatus().name() : null,
                e.getToStatus().name(), e.getNotes(), e.getAt());
    }
}
