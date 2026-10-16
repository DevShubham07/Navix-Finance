package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.query.ColumnFilter.Kind;
import com.navix.common.query.FilterColumns;
import com.navix.common.query.FilterSpec;
import com.navix.common.query.SqlFacets;
import com.navix.common.query.SqlFilter;
import com.navix.common.security.CurrentActor;
import com.navix.common.util.LikeEscape;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.AdminApplicationDtos.AdminApplicationPage;
import com.navix.loan.dto.AdminApplicationDtos.AdminApplicationView;
import com.navix.loan.dto.BureauState;
import com.navix.loan.dto.AdminApplicationDtos.RejectionView;
import com.navix.loan.dto.TelecallingDtos.TelecallingView;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.ApplicationRejection;
import com.navix.loan.entity.CustomerOwner;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.ApplicationRejectionRepository;
import com.navix.loan.repository.CustomerOwnerRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN-only register of EVERY application — complete and incomplete (DRAFT / partially filled) —
 * with full KYC detail and an onboarding-completeness summary, newest first. Backs the admin
 * "All applications" page + its CSV / PDF export.
 *
 * <p>Profiles are batch-loaded (one query) and joined per application; completeness reuses
 * {@link ApplicationVerificationService} (required steps PASS/REVIEW + agreement accepted).
 */
@Service
@RequiredArgsConstructor
public class AdminApplicationService {

    private final LoanApplicationRepository applicationRepository;
    private final CustomerProfileRepository profileRepository;
    private final ApplicationVerificationService verification;
    private final ApplicationRejectionRepository rejectionRepository;
    private final ApplicationEventRepository eventRepository;
    private final CustomerOwnerRepository ownerRepository;
    private final BureauStateService bureauStateService;
    private final com.navix.common.staff.StaffDirectory staffDirectory;
    private final CustomerTrustSignalsService trustSignals;
    private final NamedParameterJdbcTemplate jdbc;

    /** Statuses that mean the application has reached (or passed) SANCTIONED — everything else is
     *  "still pre-sanction" and belongs in the telecalling queue (work item 10). */
    static final Set<ApplicationStatus> REACHED_SANCTIONED = EnumSet.of(
            ApplicationStatus.SANCTIONED, ApplicationStatus.DISBURSEMENT_PENDING,
            ApplicationStatus.ACCOUNTANT_PENDING, ApplicationStatus.DISBURSEMENT_FAILED,
            ApplicationStatus.DISBURSED, ApplicationStatus.ACTIVE, ApplicationStatus.OVERDUE,
            ApplicationStatus.DEFAULTED, ApplicationStatus.CLOSED, ApplicationStatus.WRITTEN_OFF);

    /** Every application with full detail + completeness, newest first. ADMIN only. */
    @Transactional(readOnly = true)
    public List<AdminApplicationView> listAll() {
        requireAdmin();
        return enrich(applicationRepository.findAll().stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed()).toList());
    }

    static final int MAX_PAGE_SIZE = 100;

    /** The register's row source; the facets query reads the same one, so a dropdown can only offer what the table can show. */
    private static final String REGISTER_FROM =
            "loan_application a left join customer_profile p on p.application_id = a.id";

    /**
     * Columns the register's header panels may filter — SQL written as literals here, never built from a
     * request (see {@link FilterColumns}). Only stored columns: completeness is derived and stays a
     * separate param. Money is paise in the column and rupees on screen, hence the display scale of 100
     * on the bound, not arithmetic on the column. Identity columns (PAN, account, mobile) are TEXT only —
     * a checklist of thousands of them would be useless and a small enumeration oracle.
     */
    static final FilterColumns COLUMNS = FilterColumns.builder()
            .column("status", "a.status", Kind.IN)
            .scaled("amount", "a.amount_requested", 100, Kind.NUM)
            .column("creditScore", "p.bureau_score", Kind.NUM)
            .column("risk", "p.risk_category", Kind.IN)
            .column("name", "p.full_name", Kind.TEXT)
            .column("pan", "p.pan", Kind.TEXT)
            .column("account", "p.salary_account_number", Kind.TEXT)
            .column("ifsc", "p.salary_ifsc", Kind.TEXT)
            .column("mobile", "p.mobile", Kind.TEXT)
            .build();

    /**
     * One page of {@link #listAll()}: search ({@code q} over app id, customer id, status, name, PAN,
     * mobile, email) and the column filters run in SQL and return every matching id; the completeness
     * filter ({@code COMPLETE}/{@code INCOMPLETE}) then runs over those ids, because it is derived
     * from verification evidence, not a column; only then is the survivor list paged, so only the page
     * on screen is enriched. {@code total} is that same list's size, so the count can never disagree
     * with the pages. ADMIN only; the register has no further scope.
     */
    @Transactional(readOnly = true)
    public AdminApplicationPage page(String q, String completeness, Map<String, String> filterParams,
            int page, int size) {
        requireAdmin();
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(1, page);
        List<Long> ids = matchingIds(q, FilterSpec.parse(filterParams, COLUMNS));
        String mode = completeness == null ? "ALL" : completeness.trim().toUpperCase();
        if ("COMPLETE".equals(mode) || "INCOMPLETE".equals(mode)) {
            ids = completeAmong(ids, "COMPLETE".equals(mode));
        }
        long total = ids.size();
        int from = (int) Math.min(total, (long) (safePage - 1) * safeSize);
        List<Long> pageIds = ids.subList(from, (int) Math.min(total, from + safeSize));
        Map<Long, LoanApplication> byId = applicationRepository.findAllById(pageIds).stream()
                .collect(Collectors.toMap(LoanApplication::getId, a -> a));
        List<LoanApplication> apps = pageIds.stream().map(byId::get).filter(Objects::nonNull).toList();
        return new AdminApplicationPage(enrich(apps), safePage, safeSize, total, applicationRepository.count());
    }

    /** Ids matching the search and the column filters, newest first. Every operand is a bound parameter. */
    private List<Long> matchingIds(String q, FilterSpec spec) {
        MapSqlParameterSource params = new MapSqlParameterSource("needle", q == null || q.isBlank() ? "%"
                : "%" + LikeEscape.escapeLike(q.trim().toLowerCase()) + "%");
        String sql = "select a.id from " + REGISTER_FROM + " where ("
                + "cast(a.id as text) like :needle escape '\\'"
                + " or cast(a.customer_id as text) like :needle escape '\\'"
                + " or lower(replace(a.status, '_', ' ')) like :needle escape '\\'"
                + " or lower(p.full_name) like :needle escape '\\'"
                + " or lower(p.pan) like :needle escape '\\'"
                + " or p.mobile like :needle escape '\\'"
                + " or lower(p.email) like :needle escape '\\')"
                + SqlFilter.and(spec, COLUMNS, params)
                + " order by a.id desc";
        return jdbc.queryForList(sql, params, Long.class);
    }

    /**
     * The values a column's dropdown can offer, given every OTHER column's filter. ADMIN only, and the
     * register has no scope narrower than that (every application is visible to an admin), so the facet
     * runs unscoped — {@code SqlFacets} still gets the full register as its source.
     */
    @Transactional(readOnly = true)
    public SqlFacets.Facet facet(String column, Map<String, String> filterParams) {
        requireAdmin();
        return SqlFacets.facet(jdbc, null, REGISTER_FROM, null, FilterSpec.parse(filterParams, COLUMNS),
                COLUMNS, column, new MapSqlParameterSource());
    }

    /** The ids (order kept) whose completeness equals {@code wantComplete} — same rule as {@link #enrich}. */
    private List<Long> completeAmong(List<Long> ids, boolean wantComplete) {
        if (ids.isEmpty()) {
            return ids;
        }
        Map<Long, Integer> completed = verification.requiredPassedCounts(applicationRepository.findAllById(ids));
        Set<Long> agreed = applicationRepository.findIdsWithTermsAccepted(ids);
        int required = ApplicationVerificationService.requiredCount();
        return ids.stream()
                .filter(id -> (completed.getOrDefault(id, 0) >= required && agreed.contains(id)) == wantComplete)
                .toList();
    }

    /** Full register rows for {@code apps}, in the order given — every lookup batched over the list. */
    private List<AdminApplicationView> enrich(List<LoanApplication> apps) {
        if (apps.isEmpty()) {
            return List.of();
        }
        List<Long> appIds = apps.stream().map(LoanApplication::getId).toList();
        Map<Long, CustomerProfile> byApp = profileRepository
                .findByApplicationIdIn(appIds).stream()
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
        Map<Long, BureauState> bureauStates = bureauStateService.states(appIds);
        int required = ApplicationVerificationService.requiredCount();
        Map<Long, Integer> completedByApp = verification.requiredPassedCounts(apps);
        // Batched — one lookup per distinct assignee, one event-table query for the whole register —
        // mirrors ApplicationController#enrich / CustomerService#detail.
        Map<Long, String> executiveNameById = new java.util.LinkedHashMap<>();
        for (Long executiveId : apps.stream().map(LoanApplication::getAssignedExecutiveId)
                .filter(Objects::nonNull).distinct().toList()) {
            executiveNameById.put(executiveId,
                    staffDirectory.findStaff(executiveId).map(com.navix.common.staff.StaffSummary::name).orElse(null));
        }
        Map<Long, java.time.Instant> stageEnteredAtByAppId = new java.util.LinkedHashMap<>();
        for (ApplicationEvent event : eventRepository.findByApplicationIdInOrderByAtDesc(appIds)) {
            stageEnteredAtByAppId.putIfAbsent(event.getApplicationId(), event.getAt());
        }
        Map<Long, com.navix.common.loan.TrustSignals> trustByCustomer = trustSignals.forCustomers(
                apps.stream().map(LoanApplication::getCustomerId).filter(Objects::nonNull).distinct().toList());
        return apps.stream()
                .map(a -> {
                    CustomerProfile p = byApp.get(a.getId());
                    BureauState bureauState = bureauStates.getOrDefault(a.getId(), BureauState.NOT_FETCHED);
                    int completed = completedByApp.getOrDefault(a.getId(), 0);
                    // The retired AGREEMENT step is now the screen-1 T&C acceptance (revamp.md decision 25).
                    boolean agreement = p != null && p.getTermsAcceptedAt() != null;
                    boolean complete = completed >= required && agreement;
                    return AdminApplicationView.of(a, p, bureauState, completed, required, agreement, complete)
                            .withAssignment(executiveNameById.get(a.getAssignedExecutiveId()),
                                    stageEnteredAtByAppId.get(a.getId()))
                            .withTrust(trustByCustomer.get(a.getCustomerId()));
                })
                .toList();
    }

    /** The rejection register — every automatic and manual rejection, newest first. ADMIN only. */
    @Transactional(readOnly = true)
    public List<RejectionView> listRejections(String reasonCode) {
        requireAdmin();
        List<ApplicationRejection> rows = reasonCode == null || reasonCode.isBlank()
                ? rejectionRepository.findAllByOrderByIdDesc()
                : rejectionRepository.findByReasonCodeOrderByIdDesc(reasonCode);
        List<Long> rejectedAppIds = rows.stream().map(ApplicationRejection::getApplicationId)
                .filter(Objects::nonNull).toList();
        Map<Long, CustomerProfile> byApp = profileRepository.findByApplicationIdIn(rejectedAppIds).stream()
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
        // Not Collectors.toMap: amountRequested is null for a rejection recorded before the borrower
        // named an amount (e.g. a MANUAL credit reject on a KYC-stage lead), and toMap NPEs on a null
        // value even though the map is meant to hold one.
        Map<Long, Long> amountByApp = new java.util.HashMap<>();
        for (LoanApplication a : applicationRepository.findAllById(rejectedAppIds)) {
            amountByApp.put(a.getId(), a.getAmountRequested());
        }
        return rows.stream()
                .map(r -> RejectionView.of(r, r.getApplicationId() == null ? null : byApp.get(r.getApplicationId()),
                        r.getApplicationId() == null ? null : amountByApp.get(r.getApplicationId())))
                .toList();
    }

    /**
     * The telecaller queue (work item 10): every application whose status has not yet reached
     * SANCTIONED, enriched for a follow-up call — completeness reuses the same computation as
     * {@link #listAll()} rather than re-deriving it, plus the {@code customer_owner} assignee and a
     * staleness figure from the latest {@code application_event} (falling back to {@code createdAt}).
     * TELECALLER or ADMIN only.
     */
    @Transactional(readOnly = true)
    public List<TelecallingView> listForTelecalling() {
        requireTelecallingRole();
        // Filtered in SQL, not in memory: this queue is every pre-sanction application, so loading the
        // whole table to throw most of it away cost a full-table read on every 15s poll.
        List<LoanApplication> apps = applicationRepository.findByStatusNotIn(REACHED_SANCTIONED);
        if (apps.isEmpty()) {
            return List.of();
        }
        List<Long> appIds = apps.stream().map(LoanApplication::getId).toList();
        Map<Long, CustomerProfile> byApp = profileRepository.findByApplicationIdIn(appIds).stream()
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
        // First event per application id wins (query is ordered newest-first).
        Map<Long, Instant> latestActivity = eventRepository.findByApplicationIdInOrderByAtDesc(appIds).stream()
                .collect(Collectors.toMap(ApplicationEvent::getApplicationId, ApplicationEvent::getAt, (a, b) -> a));
        List<Long> customerIds = apps.stream().map(LoanApplication::getCustomerId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Long> ownerByCustomer = ownerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(CustomerOwner::getCustomerId, CustomerOwner::getOwnerStaffId));
        int required = ApplicationVerificationService.requiredCount();
        Map<Long, Integer> completedByApp = verification.requiredPassedCounts(apps);
        Instant now = Instant.now();
        Map<Long, com.navix.common.loan.TrustSignals> trustByCustomer = trustSignals.forCustomers(customerIds);
        return apps.stream()
                .map(a -> {
                    CustomerProfile p = byApp.get(a.getId());
                    int completed = completedByApp.getOrDefault(a.getId(), 0);
                    // LoanApplication carries no createdAt of its own — the CREATE application_event
                    // is the closest equivalent, so an application with any event history at all
                    // always resolves through latestActivity; only a row with truly no events (should
                    // not happen in practice) falls back to "just seen" rather than overstating staleness.
                    Instant last = latestActivity.get(a.getId());
                    long staleDays = last == null ? 0L : Math.max(0L, ChronoUnit.DAYS.between(last, now));
                    return new TelecallingView(a.getId(), a.getCustomerId(), a.getStatus(),
                            p != null ? p.getFullName() : null,
                            p != null ? p.getMobile() : null,
                            p != null ? p.getEmail() : null,
                            p != null ? p.getPan() : null,
                            completed, required, ownerByCustomer.get(a.getCustomerId()), staleDays,
                            trustByCustomer.get(a.getCustomerId()), a.getJourneyStep());
                })
                .sorted(Comparator.comparingLong(TelecallingView::staleDays).reversed())
                .toList();
    }

    private static void requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
    }

    private static void requireTelecallingRole() {
        CurrentActor actor = ActorContext.get();
        String role = actor == null ? null : actor.role();
        if (!"TELECALLER".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role TELECALLER or ADMIN");
        }
    }
}
