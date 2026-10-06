/**
 * What the collections worklist's DPD cell says for one row.
 *
 * The server's `dpd` floors at 0 (`LoanMath.daysPastDue`), so a loan falling due today and one due
 * next week both arrive as 0 — and the worklist deliberately lists loans up to a week BEFORE they
 * fall due, so an officer can call ahead of salary day. Telling those apart needs the due date
 * measured against TODAY IN IST: the backend derives business dates from `LocalDate.now(IST)`, and a
 * staffer's browser zone is not a business date.
 */

import { daysBetween } from "@/lib/calc/loan-math";
import { isoDayToLocalDate, istCalendarToday } from "@/lib/customers/customer-360";

export type WorklistDueCue =
  | { kind: "overdue"; days: number }
  | { kind: "due-today" }
  | { kind: "due-in"; days: number }
  /** No due date on record, or a past due date the server has not (yet) counted as late. */
  | { kind: "none" };

export type WorklistDueRow = { dpd: number; dueDate: string | null };

/**
 * Classify one worklist row.
 *
 * - A positive server DPD always wins — the server is the authority on lateness.
 * - Otherwise the due date is compared with the IST calendar day: today is "due today", a later
 *   date is "due in N d".
 * - A past due date with DPD 0 is stale data (the server counts the day after the due date as 1),
 *   so it is not dressed up as anything the API did not confirm.
 */
export function worklistDueCue(row: WorklistDueRow, now: Date = new Date()): WorklistDueCue {
  if (row.dpd > 0) return { kind: "overdue", days: row.dpd };
  const due = isoDayToLocalDate(row.dueDate);
  if (!due) return { kind: "none" };
  const days = daysBetween(istCalendarToday(now), due);
  if (days === 0) return { kind: "due-today" };
  if (days > 0) return { kind: "due-in", days };
  return { kind: "none" };
}

/** The cell text: the DPD count when late (or unknown), otherwise how far away the due date is. */
export function worklistDueLabel(cue: WorklistDueCue): string {
  switch (cue.kind) {
    case "overdue":
      return String(cue.days);
    case "due-today":
      return "due today";
    case "due-in":
      return `due in ${cue.days} d`;
    default:
      return "0";
  }
}
