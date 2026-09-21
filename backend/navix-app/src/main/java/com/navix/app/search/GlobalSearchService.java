package com.navix.app.search;

import com.navix.app.search.GlobalSearchDtos.SearchGroup;
import com.navix.app.search.GlobalSearchDtos.SearchItem;
import com.navix.app.search.GlobalSearchDtos.SearchResponse;
import com.navix.collections.dto.CollectionsDtos.CaseView;
import com.navix.collections.service.CollectionsService;
import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.security.ActorContext;
import com.navix.common.util.Masking;
import com.navix.iam.service.BlocklistService;
import com.navix.iam.service.StaffService;
import com.navix.loan.dto.CustomerDtos.CustomerSummary;
import com.navix.loan.dto.LeadDtos.LeadView;
import com.navix.loan.dto.LoanRegisterDtos.LoanRegisterRow;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.service.ApplicationFlowService;
import com.navix.loan.service.CustomerService;
import com.navix.loan.service.LeadService;
import com.navix.loan.service.LoanRegisterService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff console's global search — one query fanned out across the entity families the caller's
 * role can already see.
 *
 * <p><b>Search widens the path to the data, never the data itself.</b> Every group delegates to the
 * SAME service the corresponding list page calls ({@link CustomerService#page},
 * {@link LoanRegisterService#list}, {@link LeadService#list}, …), so each one applies its own
 * {@code requireRole} and its own per-actor scoping — a Credit Executive's applications stay limited
 * to files assigned to them, a non-head's customers stay limited to their own book. Querying the
 * repositories directly here would have quietly bypassed all of it.
 *
 * <p>Groups run <b>sequentially</b>, not in parallel: {@link ActorContext} is a ThreadLocal, so work
 * handed to another thread would execute as {@code SYSTEM} and silently lose every scope above.
 *
 * <p>A group the role cannot see is omitted from the response entirely rather than returned empty —
 * "nothing matched" and "you may not look" must not be distinguishable, or the palette becomes an
 * oracle for whether a customer exists.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GlobalSearchService {

    /** Kill switch. Defaults ON when the row is missing, so a fresh environment still has search. */
    static final String FLAG = "global-search";

    private static final int MAX_PER_GROUP = 10;
    private static final int DEFAULT_PER_GROUP = 5;

    private final CustomerService customerService;
    private final ApplicationFlowService applicationFlowService;
    private final LoanRegisterService loanRegisterService;
    private final CollectionsService collectionsService;
    private final LeadService leadService;
    private final StaffService staffService;
    private final BlocklistService blocklistService;
    private final CustomerProfileRepository profileRepository;
    private final FeatureFlagService featureFlags;

    public SearchResponse search(String rawQuery, int perGroup) {
        String role = requireSearchingStaff();
        if (!featureFlags.isEnabled(FLAG, true)) {
            throw new BusinessException("FEATURE_DISABLED", "Global search is switched off");
        }

        SearchQuery query = SearchQuery.parse(rawQuery);
        if (!query.searchable()) {
            return new SearchResponse(query.needle(), query.interpretedAs(), List.of());
        }
        int cap = Math.max(1, Math.min(perGroup, MAX_PER_GROUP));

        List<SearchGroup> groups = new ArrayList<>();
        for (Group group : groupsFor(role)) {
            addGroup(groups, group, query, cap);
        }
        return new SearchResponse(query.needle(), query.interpretedAs(), groups);
    }

    /**
     * Staff only, and never a DSA.
     *
     * <p>DSA is an authz <em>exclusion</em>, not merely an absence of permissions: it satisfies
     * {@code hasRole("STAFF")} at the namespace gate, so every staff-open surface has to reject it
     * by name — as {@code CustomerController.requireStaff} and {@code CustomerService.rejectDsa}
     * already do. This is one of those surfaces.
     */
    private String requireSearchingStaff() {
        String role = ActorContext.get().role();
        if (role == null || "BORROWER".equals(role) || "ANONYMOUS".equals(role) || "SYSTEM".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff role required");
        }
        if ("DSA".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "DSAs cannot search the console");
        }
        return role;
    }

    /** The families a role may search — a mirror of the sidebar permissions, in backend terms. */
    private enum Group {
        CUSTOMER("customer", "Customers"),
        APPLICATION("application", "Applications"),
        LOAN("loan", "Loans"),
        COLLECTIONS("collections", "Collections"),
        LEAD("lead", "Leads"),
        STAFF("staff", "Staff"),
        BLOCKLIST("blocklist", "Blocklist");

        final String kind;
        final String label;

        Group(String kind, String label) {
            this.kind = kind;
            this.label = label;
        }
    }

    private static List<Group> groupsFor(String role) {
        return switch (role) {
            case "ADMIN" -> List.of(Group.CUSTOMER, Group.APPLICATION, Group.LOAN, Group.COLLECTIONS,
                    Group.LEAD, Group.STAFF, Group.BLOCKLIST);
            case "COLLECTION_HEAD" -> List.of(Group.CUSTOMER, Group.APPLICATION, Group.COLLECTIONS, Group.LOAN);
            case "COLLECTION_EXECUTIVE" -> List.of(Group.CUSTOMER, Group.APPLICATION, Group.COLLECTIONS);
            case "TELECALLER" -> List.of(Group.CUSTOMER, Group.LEAD);
            // Credit / disbursement / accounting all work the application pipeline over the book.
            default -> List.of(Group.CUSTOMER, Group.APPLICATION);
        };
    }

    /**
     * Run one group, or drop it.
     *
     * <p>A {@code FORBIDDEN_ROLE} from a delegate is defence in depth working as intended — the role
     * matrix above and the service's own guard disagreed, and the service wins. It costs this group,
     * never the whole search: one mis-mapped role must not break the palette for everything else.
     */
    private void addGroup(List<SearchGroup> out, Group group, SearchQuery query, int cap) {
        try {
            List<SearchItem> items = switch (group) {
                case CUSTOMER -> customers(query, cap + 1);
                case APPLICATION -> applications(query, cap + 1);
                case LOAN -> loans(query, cap + 1);
                case COLLECTIONS -> collections(query, cap + 1);
                case LEAD -> leads(query, cap + 1);
                case STAFF -> staff(query, cap + 1);
                case BLOCKLIST -> blocklist(query, cap + 1);
            };
            if (items.isEmpty()) {
                return;
            }
            boolean more = items.size() > cap;
            out.add(new SearchGroup(group.kind, group.label,
                    items.stream().limit(cap).toList(), more));
        } catch (BusinessException e) {
            if ("FORBIDDEN_ROLE".equals(e.getCode())) {
                return;
            }
            throw e;
        }
    }

    // ---- groups ---------------------------------------------------------------------------

    private List<SearchItem> customers(SearchQuery q, int limit) {
        return customerService.page(q.needle(), null, null, null, false, 1, limit).rows().stream()
                .map(this::toCustomerItem)
                .toList();
    }

    private SearchItem toCustomerItem(CustomerSummary c) {
        String status = c.loanStatus() != null ? c.loanStatus() : c.latestStatus();
        return new SearchItem(
                Group.CUSTOMER.kind,
                String.valueOf(c.customerId()),
                c.name() != null ? c.name() : "Customer #" + c.customerId(),
                joinDetail(Masking.maskPhone(c.mobile()), Masking.maskPan(c.pan())),
                meta("outstandingPaise", c.totalOutstandingPaise()),
                "/staff/customers/" + c.customerId(),
                status);
    }

    private List<SearchItem> applications(SearchQuery q, int limit) {
        List<LoanApplication> apps = applicationFlowService.search(q.needle(), limit);
        if (apps.isEmpty()) {
            return List.of();
        }
        Map<Long, CustomerProfile> profiles = profileRepository
                .findByApplicationIdIn(apps.stream().map(LoanApplication::getId).toList()).stream()
                .collect(Collectors.toMap(CustomerProfile::getApplicationId, p -> p, (a, b) -> a));
        return apps.stream().map(app -> {
            CustomerProfile profile = profiles.get(app.getId());
            // No KYC name yet (an intake still in DRAFT/KYC_PENDING): the "#id · " prefix below
            // already carries the id, so repeating it as the name reads "#9002 · Application #9002".
            String name = profile != null && profile.getFullName() != null && !profile.getFullName().isBlank()
                    ? profile.getFullName() : "Unnamed applicant";
            Long amount = app.getSanctionedAmountPaise() != null
                    ? app.getSanctionedAmountPaise() : app.getAmountRequested();
            return new SearchItem(
                    Group.APPLICATION.kind,
                    String.valueOf(app.getId()),
                    "#" + app.getId() + " · " + name,
                    joinDetail(profile == null ? null : Masking.maskPhone(profile.getMobile()),
                            app.getLoanId() == null ? null : "Loan #" + app.getLoanId()),
                    meta("amountPaise", amount),
                    // The queue page prefills its own search from ?q=, landing on this one file.
                    "/staff/applications?q=" + encode(String.valueOf(app.getId())),
                    app.getStatus() == null ? null : app.getStatus().name());
        }).toList();
    }

    private List<SearchItem> loans(SearchQuery q, int limit) {
        return loanRegisterService.list(null, q.needle(), null, null).stream()
                .limit(limit)
                .map(this::toLoanItem)
                .toList();
    }

    private SearchItem toLoanItem(LoanRegisterRow row) {
        return new SearchItem(
                Group.LOAN.kind,
                String.valueOf(row.loanId()),
                "#" + row.loanId() + " · "
                        + (row.borrowerName() != null ? row.borrowerName() : "Loan"),
                joinDetail(Masking.maskPhone(row.mobile()), row.panMasked(),
                        row.dueDate() == null ? null : "due " + row.dueDate()),
                meta("outstandingPaise", row.outstandingPaise()),
                "/staff/loans?q=" + encode(String.valueOf(row.loanId()))
                        + "&open=" + row.loanId(),
                row.status());
    }

    /**
     * Collections cases have no server-side {@code q} — the page loads the worklist whole and filters
     * in the browser. Matching in memory here is the same data by the same guard, and keeps this
     * from being the one group that needs a new query path.
     */
    private List<SearchItem> collections(SearchQuery q, int limit) {
        String needle = q.needle().toLowerCase(Locale.ROOT);
        return collectionsService.listCaseViews().stream()
                .filter(c -> matchesCase(c, needle))
                .limit(limit)
                .map(c -> new SearchItem(
                        Group.COLLECTIONS.kind,
                        String.valueOf(c.loanId()),
                        "#" + c.loanId() + " · "
                                + (c.borrowerName() != null ? c.borrowerName() : "Case"),
                        joinDetail("DPD " + c.dpd(), c.bucket() == null ? null : c.bucket().name()),
                        meta("outstandingPaise", c.outstandingPaise()),
                        "/staff/collections/" + c.loanId(),
                        c.loanStatus()))
                .toList();
    }

    private static boolean matchesCase(CaseView c, String needle) {
        return (c.loanId() != null && String.valueOf(c.loanId()).startsWith(needle))
                || containsIgnoreCase(c.borrowerName(), needle);
    }

    private List<SearchItem> leads(SearchQuery q, int limit) {
        return leadService
                .list(q.needle(), null, null, null, null, null, null, null, null, 1, limit)
                .rows().stream()
                .map(this::toLeadItem)
                .toList();
    }

    private SearchItem toLeadItem(LeadView lead) {
        return new SearchItem(
                Group.LEAD.kind,
                String.valueOf(lead.id()),
                lead.name() != null ? lead.name() : "Lead #" + lead.id(),
                joinDetail(Masking.maskPhone(lead.mobile()), lead.city()),
                null,
                "/staff/leads?q=" + encode(lead.mobile() != null ? lead.mobile() : lead.name()),
                lead.callStatus());
    }

    /** ADMIN-only, and the roster is small — {@code listStaff()} has no {@code q} to push into. */
    private List<SearchItem> staff(SearchQuery q, int limit) {
        String needle = q.needle().toLowerCase(Locale.ROOT);
        return staffService.listStaff().stream()
                .filter(s -> containsIgnoreCase(s.name(), needle) || containsIgnoreCase(s.email(), needle))
                .limit(limit)
                .map(s -> new SearchItem(
                        Group.STAFF.kind,
                        String.valueOf(s.id()),
                        s.name() != null ? s.name() : s.email(),
                        joinDetail(s.email(), s.role() == null ? null : s.role().name()),
                        null,
                        "/staff/admin/staff?q=" + encode(s.name() != null ? s.name() : s.email()),
                        s.status() == null ? null : s.status().name()))
                .toList();
    }

    private List<SearchItem> blocklist(SearchQuery q, int limit) {
        String needle = q.needle().toLowerCase(Locale.ROOT);
        return blocklistService.listActive().stream()
                .filter(b -> containsIgnoreCase(b.getValue(), needle))
                .limit(limit)
                .map(b -> new SearchItem(
                        Group.BLOCKLIST.kind,
                        String.valueOf(b.getId()),
                        maskIdentifier(b.getValue()),
                        b.getReason(),
                        null,
                        "/staff/admin/blocklist?q=" + encode(b.getValue()),
                        b.getType() == null ? null : b.getType().name()))
                .toList();
    }

    // ---- helpers --------------------------------------------------------------------------

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** " · "-joined, skipping the parts a given record happens not to have. */
    private static String joinDetail(String... parts) {
        String joined = java.util.Arrays.stream(parts)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" · "));
        return joined.isEmpty() ? null : joined;
    }

    private static Map<String, Object> meta(String key, Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(key, value);
        return meta;
    }

    /**
     * A blocklist row's value may be a PAN, a mobile or an email — mask whichever it is, with the
     * same {@link Masking} helpers so one identifier never renders two ways.
     */
    private static String maskIdentifier(String value) {
        if (value == null) {
            return null;
        }
        if (value.contains("@")) {
            return Masking.maskEmail(value);
        }
        return value.chars().allMatch(Character::isDigit)
                ? Masking.maskPhone(value)
                : Masking.maskPan(value);
    }

    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
