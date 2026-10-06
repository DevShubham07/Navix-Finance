/**
 * What the Loans register's DPD cell says for one row. The backend's `dpd` is 0 both for a loan
 * that falls due today and for one whose due date is still ahead (`LoanMath.daysPastDue` floors at
 * 0), so the cell used to read "—" for both. Telling them apart needs the due date measured against
 * TODAY IN IST: the backend derives business dates from `LocalDate.now(IST)`, and a staffer's
 * browser zone is not a business date.
 */

import { iso } from "@/lib/period";
import { istCalendarToday } from "@/lib/customers/customer-360";
import { segmentOf } from "./segments";

export type LoanRegisterDpd =
  | { kind: "overdue"; days: number }
  | { kind: "due-today" }
  | { kind: "not-due" }
  /** Closed, no due date on record, or a past due date the server has not yet counted as late. */
  | { kind: "none" };

export type LoanRegisterDpdRow = {
  dpd: number;
  dueDate: string | null;
  closedOn: string | null;
  status?: string | null;
};

/**
 * Classify one register row.
 *
 * - Any positive server DPD wins, closed or not — a settled loan's lateness is frozen at the day it
 *   closed and stays worth showing.
 * - A closed loan (status REPAID/CLOSED, or a stored `closedOn`) is never "due today" or "not due":
 *   it has nothing left to fall due.
 * - A past due date with DPD 0 is stale data (the server counts the day after the due date as 1),
 *   so it reads "—" rather than claiming a state the API did not confirm.
 */
export function loanRegisterDpd(row: LoanRegisterDpdRow, now: Date = new Date()): LoanRegisterDpd {
  if (row.dpd > 0) return { kind: "overdue", days: row.dpd };
  if (row.closedOn || segmentOf(row) === "closed") return { kind: "none" };
  const due = /^\d{4}-\d{2}-\d{2}/.exec(row.dueDate ?? "")?.[0];
  if (!due) return { kind: "none" };
  const today = iso(istCalendarToday(now));
  if (due === today) return { kind: "due-today" };
  // yyyy-mm-dd strings order the same way the dates do.
  if (due > today) return { kind: "not-due" };
  return { kind: "none" };
}

export function loanRegisterDpdLabel(state: LoanRegisterDpd): string {
  switch (state.kind) {
    case "overdue":
      return `${state.days}d`;
    case "due-today":
      return "due today";
    case "not-due":
      return "not due";
    default:
      return "—";
  }
}
