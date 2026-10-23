package com.navix.common.collections;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Port for counting collections activity per staff member from modules that must not depend on
 * {@code navix-collections} internals — specifically {@code navix-loan}'s staff-performance
 * dashboard, which reports "calls made" alongside the credit decisions it reads directly.
 * Implemented by the collections module ({@code CollectionActivityDirectoryAdapter}); the bootable
 * app wires the bean by component scan, the same seam as {@link CollectionCaseDirectory}.
 *
 * <p>Deliberately <b>batched</b>, for the same reason {@link CollectionCaseDirectory} is: the caller
 * renders a whole roster in one read, and a per-staffer lookup would be an N+1 across the company.
 */
public interface CollectionActivityDirectory {

    /**
     * How many interactions each of {@code staffIds} logged in the half-open window
     * {@code [from, to)}. Staff who logged none are absent from the map rather than mapped to 0, so
     * callers decide how to render "none" themselves.
     *
     * <p>Only interactions carrying a staff id are counted. Rows predating V59 have none — they
     * recorded no actor at all — so a window before that migration legitimately yields nothing, and
     * callers must not present that as "this person made no calls".
     *
     * @param staffIds the staff ids to count for; an empty collection yields an empty map
     * @return staff id → interaction count, only for staff with at least one
     */
    Map<Long, Long> callCountsByStaff(Collection<Long> staffIds, Instant from, Instant to);

    /**
     * One staffer's collections work, plus the instant of every action behind it so the caller can
     * fold collections activity into its own "active days" / first-last / per-day figures.
     */
    record StaffActivity(CollectionStaffStats stats, List<Instant> activityAt) {
    }

    /**
     * Full collections tallies for each of {@code staffIds} over the half-open window
     * {@code [from, to)} — interactions by outcome, payments recorded and recovered, settlements
     * proposed/decided, Accountant validations — plus each staffer's live open-case count. Staff with
     * no collections footprint at all are absent from the map.
     *
     * @param staffIds the staff ids to tally; an empty collection yields an empty map
     */
    Map<Long, StaffActivity> statsByStaff(Collection<Long> staffIds, Instant from, Instant to);
}
