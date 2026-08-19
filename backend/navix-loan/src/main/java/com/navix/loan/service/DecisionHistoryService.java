package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    /**
     * Actions that count as a decision — the rest of the trail is routing/audit noise.
     *
     * <p>Public because {@code CustomerService} scopes an executive's customer list by "applications
     * I decided on" and must use the SAME definition of a decision; duplicating the set there would
     * let the two surfaces drift apart silently.
     */
    public static final Set<String> DECISION_ACTIONS = Set.of(
            "KYC_APPROVE", "KYC_REJECT", "ASSIGN", "REASSIGN", "SANCTION", "REJECT_LEAD", "MARK_PENDING",
            "EXEC_APPROVE", "EXEC_REJECT", "HEAD_APPROVE", "HEAD_REJECT",
            "DISB_ACCEPT", "DISB_REJECT", "VALIDATE_SUCCESS", "VALIDATE_FAIL", "RETRY", "CANCEL");

    /** Which roles a Head may inspect. Heads only — everyone else sees just themselves. */
    private static final Map<String, Set<String>> TEAM_OF = Map.of(
            "CREDIT_HEAD", Set.of("CREDIT_HEAD", "CREDIT_EXECUTIVE"),
            "COLLECTION_HEAD", Set.of("COLLECTION_HEAD", "COLLECTION_EXECUTIVE"));

    private final ApplicationEventRepository eventRepository;
    private final CustomerProfileRepository profileRepository;
    private final LoanApplicationRepository applicationRepository;
    private final StaffDirectory staffDirectory;

    /**
     * One decision, with {@code application_event.notes} already parsed into typed columns by
     * {@link DecisionNotes}. The raw {@code notes} stays on the wire as the audit source of truth —
     * the UI renders it only as a hover title, never as a visible column.
     */
    public record DecisionView(Long applicationId, Long customerId, String customerName, String pan,
                               String action, String fromStatus, String toStatus, Instant at,
                               Long amountPaise, Integer salaryCreditDay, LocalDate repaymentDate,
                               Long assigneeId, String assigneeName, String txnRef, String remark,
                               String notes) {
    }

    /** {@code staffId} null → the caller's own history. */
    @Transactional(readOnly = true)
    public List<DecisionView> decisions(Long staffId) {
        String callerId = ActorContext.get().id();
        String target = staffId != null ? String.valueOf(staffId) : callerId;
        if (!target.equals(callerId)) {
            requireCanInspect(staffId);
        }
        List<ApplicationEvent> events = eventRepository.findByActorIdOrderByAtDesc(target).stream()
                .filter(e -> DECISION_ACTIONS.contains(e.getAction()))
                .toList();
        if (events.isEmpty()) {
            return List.of();
        }

        // Batch every join up front. Resolving the customer/profile per row was an N+1 that grew
        // with a staffer's career; this is a fixed 2 queries regardless of how many decisions they
        // have made.
        List<Long> appIds = events.stream().map(ApplicationEvent::getApplicationId).distinct().toList();
        Map<Long, CustomerProfile> profileByApp = profileRepository.findByApplicationIdIn(appIds).stream()
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, Function.identity(), (a, b) -> a));
        Map<Long, Long> customerIdByApp = applicationRepository.findAllById(appIds).stream()
                .collect(Collectors.toMap(LoanApplication::getId, LoanApplication::getCustomerId, (a, b) -> a));

        // Assignee names are memoised over DISTINCT ids — bounded by the number of executives, not
        // the number of rows. StaffDirectory has no batch lookup, so never call it per row.
        Map<Long, String> assigneeNames = new HashMap<>();
        return events.stream()
                .map(e -> view(e, profileByApp, customerIdByApp, assigneeNames))
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

    private DecisionView view(ApplicationEvent e, Map<Long, CustomerProfile> profileByApp,
                             Map<Long, Long> customerIdByApp, Map<Long, String> assigneeNames) {
        CustomerProfile profile = profileByApp.get(e.getApplicationId());
        DecisionNotes.Parsed parsed = DecisionNotes.parse(e.getNotes());
        String assigneeName = parsed.assigneeId() == null ? null
                : assigneeNames.computeIfAbsent(parsed.assigneeId(),
                        id -> staffDirectory.findStaff(id).map(StaffSummary::name).orElse(null));
        return new DecisionView(
                e.getApplicationId(),
                customerIdByApp.get(e.getApplicationId()),
                profile != null ? profile.getFullName() : null,
                profile != null ? profile.getPan() : null,
                e.getAction(),
                e.getFromStatus() != null ? e.getFromStatus().name() : null,
                e.getToStatus().name(),
                e.getAt(),
                parsed.amountPaise(),
                parsed.salaryCreditDay(),
                parsed.repaymentDate(),
                parsed.assigneeId(),
                assigneeName,
                parsed.txnRef(),
                parsed.remark(),
                e.getNotes());
    }
}
