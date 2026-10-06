/**
 * Pure helpers for the `/staff/telecalling` queue sections.
 */

/**
 * Whether every row on the visible page is ticked. `false` for an empty page, so the header
 * checkbox never reads as "all selected" over nothing.
 */
export function isPageFullySelected(selected: ReadonlySet<number>, pageIds: readonly number[]): boolean {
  return pageIds.length > 0 && pageIds.every((id) => selected.has(id));
}

/**
 * The header "Select all" box, scoped to the VISIBLE page.
 *
 * It used to tick every row in the section across all pages, so an operator looking at 25 rows
 * could send reminders to 140 borrowers. Now: if the whole page is already ticked, untick the
 * page; otherwise tick the rest of it. Ticks on other pages (made one by one before paging) are
 * left alone either way.
 */
export function togglePageSelection(selected: ReadonlySet<number>, pageIds: readonly number[]): Set<number> {
  const next = new Set(selected);
  if (isPageFullySelected(selected, pageIds)) {
    for (const id of pageIds) next.delete(id);
  } else {
    for (const id of pageIds) next.add(id);
  }
  return next;
}

/**
 * How many queue rows are owned by someone other than the signed-in staffer. Neither section lists
 * them, so without this count they were invisible. `null` while the staffer's id is unknown — every
 * owned row would otherwise count as "someone else's".
 */
export function countAssignedToOthers(
  rows: readonly { ownerStaffId: number | null }[],
  myId: number | null,
): number | null {
  if (myId == null) return null;
  return rows.filter((r) => r.ownerStaffId != null && r.ownerStaffId !== myId).length;
}

/**
 * The muted line under a section header. The queue only knows an owner's staff id, not their role,
 * so it says "other staff" rather than "other telecallers": a Head or an Admin can own a customer.
 */
export function assignedToOthersLabel(count: number): string {
  return count === 1
    ? "1 application assigned to other staff isn't shown."
    : `${count} applications assigned to other staff aren't shown.`;
}

/** Fill of the completeness bar, 0–100. A queue with no required steps reads as empty, not NaN. */
export function completenessPercent(completed: number, required: number): number {
  if (!(required > 0) || !Number.isFinite(completed)) return 0;
  return Math.min(100, Math.max(0, Math.round((completed / required) * 100)));
}
