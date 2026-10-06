/**
 * Small, dependency-free decisions behind the staff Customers register (`/staff/customers`).
 * Kept out of the page so they can be unit-tested without rendering it.
 */

/**
 * What a register row's "Open" and ⓘ (quick summary) buttons should open.
 *
 * The row already carries its latest application's id (`CustomerSummary.latestApplicationId`). The
 * backend picks it exactly as the customer roll-up orders `CustomerDetail.applications` — the
 * highest application id — so opening on it shows the same file the roll-up would have resolved
 * to, without first fetching the whole roll-up (profile, every application, loan, payment and the
 * credit brief) just to read that id back. Only a customer with no application on file has nothing
 * to open but the customer view.
 */
export type CustomerRowTarget =
  | { kind: "application"; applicationId: number }
  | { kind: "customer"; customerId: number };

export function customerRowTarget(row: {
  customerId: number;
  latestApplicationId?: number | null;
}): CustomerRowTarget {
  return row.latestApplicationId != null
    ? { kind: "application", applicationId: row.latestApplicationId }
    : { kind: "customer", customerId: row.customerId };
}

/**
 * True when two date windows select different rows, so paging has to restart at page 1.
 *
 * A missing bound equals a missing bound (`undefined` and `""` both mean "open-ended", the same
 * normalisation the page applies to its query keys), so switching between two periods that both
 * mean "no window" — All time and an empty Custom range — keeps the reader on the page they are on.
 */
export function dateWindowChanged(
  a: { from?: string; to?: string },
  b: { from?: string; to?: string },
): boolean {
  return (a.from ?? "") !== (b.from ?? "") || (a.to ?? "") !== (b.to ?? "");
}
