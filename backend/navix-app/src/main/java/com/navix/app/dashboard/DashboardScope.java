package com.navix.app.dashboard;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActingRole;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Who a dashboard request is about, decided once and server-side.
 *
 * <p>{@code view} is the dashboard-local role toggle. It never grants anything: it must be the
 * caller's real role, or a sub-view {@link ActingRole#allowed} lets that role adopt (ADMIN may take
 * every desk). Authorization is the REAL JWT role, exactly as for the "work as" header.
 *
 * @param staffIds the staff the numbers are about. For a non-ADMIN view this is the roster the view
 *                 stands for, intersected with the request; for ADMIN it is just the request
 *                 (empty = everyone).
 * @param orgWide  true only for the ADMIN view: no roster scoping is applied to the book.
 * @param realRole the caller's real role
 * @param selfId   the caller's staff id
 */
public record DashboardScope(String view, List<Long> staffIds, boolean orgWide, String realRole, Long selfId) {

    public static final Set<String> VIEWS = Set.of("ADMIN", "CREDIT_HEAD", "CREDIT_EXECUTIVE",
            "COLLECTION_HEAD", "COLLECTION_EXECUTIVE", "TELECALLER", "DISBURSEMENT_HEAD", "ACCOUNTANT");

    /** Roles whose holder only ever sees their own numbers. */
    private static final Set<String> SELF_ONLY = Set.of("CREDIT_EXECUTIVE", "COLLECTION_EXECUTIVE", "TELECALLER");

    public static DashboardScope resolve(CurrentActor actor, String requestedView, List<Long> requestedIds,
                                         StaffDirectory directory) {
        String role = requireStaff(actor);
        String view = requestedView == null ? "" : requestedView.trim().toUpperCase(java.util.Locale.ROOT);
        if (!VIEWS.contains(view) || !(view.equals(role) || ActingRole.allowed(role, view))) {
            throw new BusinessException("FORBIDDEN_ROLE", "You cannot open the " + view + " dashboard view");
        }
        Long self = selfId(actor);
        List<Long> requested = requestedIds == null ? List.of() : requestedIds;

        if (view.equals("ADMIN")) {
            return new DashboardScope(view, List.copyOf(requested), true, role, self);
        }
        List<Long> roster;
        if (SELF_ONLY.contains(role) && SELF_ONLY.contains(view)) {
            roster = List.of(self);
            requested = List.of(); // a real executive is forced to [self], whatever they ask for
        } else {
            roster = rosterFor(view, directory);
        }
        List<Long> ids = requested.isEmpty() ? roster : roster.stream().filter(requested::contains).toList();
        return new DashboardScope(view, List.copyOf(ids), false, role, self);
    }

    /** A scope over an explicit set of staff — for queries that are keyed by staff, not by view. */
    public static DashboardScope forStaff(String view, List<Long> ids) {
        return new DashboardScope(view, List.copyOf(ids), false, view, null);
    }

    /** The people a non-ADMIN view stands for. Views with no roster (disbursement, accounts) have none. */
    private static List<Long> rosterFor(String view, StaffDirectory directory) {
        List<StaffSummary> staff = new ArrayList<>();
        switch (view) {
            case "CREDIT_HEAD" -> {
                staff.addAll(directory.listActive("CREDIT_HEAD"));
                staff.addAll(directory.listActive("CREDIT_EXECUTIVE"));
            }
            case "COLLECTION_HEAD" -> {
                staff.addAll(directory.listActive("COLLECTION_HEAD"));
                staff.addAll(directory.listActive("COLLECTION_EXECUTIVE"));
            }
            case "CREDIT_EXECUTIVE", "COLLECTION_EXECUTIVE", "TELECALLER" -> staff.addAll(directory.listActive(view));
            default -> { /* no roster */ }
        }
        return staff.stream().map(StaffSummary::id).toList();
    }

    /** Staff only; never a borrower, an anonymous token, the system actor — or a DSA (an authz exclusion). */
    static String requireStaff(CurrentActor actor) {
        String role = actor.role();
        if (role == null || "BORROWER".equals(role) || "ANONYMOUS".equals(role) || "SYSTEM".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff role required");
        }
        if ("DSA".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "DSAs cannot view the dashboard");
        }
        return role;
    }

    private static Long selfId(CurrentActor actor) {
        try {
            return Long.valueOf(actor.id());
        } catch (RuntimeException e) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff identity required");
        }
    }

    /** True when {@code view} is one of {@code allowed}. */
    public boolean viewIn(String... allowed) {
        return List.of(allowed).contains(view);
    }

    /** Throws FORBIDDEN_ROLE unless the view is one of {@code allowed}. */
    public DashboardScope require(String... allowed) {
        if (!viewIn(allowed)) {
            throw new BusinessException("FORBIDDEN_ROLE", "The " + view + " view cannot read this data");
        }
        return this;
    }

    /** Roster filter for staff-keyed boards: ADMIN with no explicit request sees everyone. */
    public <T> List<T> filterStaff(List<T> all, java.util.function.Function<T, Long> idOf) {
        if (orgWide && staffIds.isEmpty()) return all;
        return all.stream().filter(t -> staffIds.contains(idOf.apply(t))).toList();
    }

    /** Ids for an SQL {@code in (:ids)} -- never empty, since {@code in ()} is a syntax error. */
    public List<Long> sqlIds() {
        return staffIds.isEmpty() ? List.of(-1L) : staffIds;
    }

    /** True when staff-keyed metrics should not be restricted to {@link #staffIds()}. */
    public boolean allStaff() {
        return orgWide && staffIds.isEmpty();
    }
}
