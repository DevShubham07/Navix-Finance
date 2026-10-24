/**
 * How a borrower behaved on one loan, for the loan pills in the customer pop-up's Loan tab.
 *
 * Pure: one loan + today (+ the server's outstanding row, for the settlement flag) → a kind, a
 * short label and the signed day count against the due date. Closed loans are judged on when they
 * closed; running loans on where today sits against the due date. All day arithmetic is calendar
 * days via `daysBetween` (signed, b − a).
 */
import { daysBetween } from "@/lib/calc/loan-math";
import { isoDayToLocalDate } from "@/lib/customers/customer-360";
import { loanGraceDays, type LoanView, type OutstandingView } from "@/lib/api/applications";

export type LoanBehaviourKind = "early" | "onTime" | "late" | "veryLate" | "open" | "overdue";

export interface LoanBehaviour {
  kind: LoanBehaviourKind;
  /** e.g. "Paid 5 days early", "Paid on time", "Settled", "Due in 12 days", "3 days overdue". */
  label: string;
  /** The same, short enough for the pill face: "5d early", "on time", "settled", "due in 12d", "3d overdue". */
  short: string;
  /**
   * Closed loans: closedOn − dueDate. Running loans: today − dueDate. Positive = after the due
   * date. Null when the loan has no due date (or a closed loan has no closedOn).
   */
  daysVsDue: number | null;
}

/** Closed this many days (or more) before the due date = early. */
export const EARLY_THRESHOLD_DAYS = 3;
/** Closed more than this many days after the due date = very late. */
export const LATE_UNTIL_DAYS_AFTER_DUE = 7;

const plural = (n: number, unit: string) => `${n} ${unit}${n === 1 ? "" : "s"}`;

/** Derives the pill-face wording from the full label's kind and day count. */
function withShort(b: Omit<LoanBehaviour, "short">): LoanBehaviour {
  const d = b.daysVsDue;
  let short: string;
  switch (b.kind) {
    case "early":
      short = `${-(d ?? 0)}d early`;
      break;
    case "onTime":
      short = b.label === "Paid" ? "paid" : "on time";
      break;
    case "late":
      short = `${d}d late`;
      break;
    case "veryLate":
      short = b.label === "Settled" ? "settled" : `${d}d late`;
      break;
    case "overdue":
      short = `${d}d overdue`;
      break;
    default:
      short = d == null ? "no due date" : d === 0 ? "due today" : `due in ${-d}d`;
  }
  return { ...b, short };
}

export function loanBehaviour(
  loan: LoanView,
  today: Date,
  outstanding?: OutstandingView | null,
): LoanBehaviour {
  return withShort(judge(loan, today, outstanding));
}

function judge(loan: LoanView, today: Date, outstanding?: OutstandingView | null): Omit<LoanBehaviour, "short"> {
  const closed = loan.status === "CLOSED" || loan.closedOn != null;
  const due = isoDayToLocalDate(loan.dueDate);

  if (closed) {
    const closedOn = isoDayToLocalDate(loan.closedOn);
    // An approved settlement closed the file for less than was owed — the worst outcome, whatever the day.
    if (outstanding?.settledAmountPaise != null) {
      return { kind: "veryLate", label: "Settled", daysVsDue: due && closedOn ? daysBetween(due, closedOn) : null };
    }
    if (!due || !closedOn) return { kind: "onTime", label: "Paid", daysVsDue: null };
    const d = daysBetween(due, closedOn);
    if (d <= -EARLY_THRESHOLD_DAYS) return { kind: "early", label: `Paid ${plural(-d, "day")} early`, daysVsDue: d };
    if (d <= loanGraceDays(loan)) return { kind: "onTime", label: "Paid on time", daysVsDue: d };
    if (d <= LATE_UNTIL_DAYS_AFTER_DUE) return { kind: "late", label: `Paid ${plural(d, "day")} late`, daysVsDue: d };
    return { kind: "veryLate", label: `Paid ${plural(d, "day")} late`, daysVsDue: d };
  }

  if (!due) return { kind: "open", label: "No due date yet", daysVsDue: null };
  const d = daysBetween(due, today);
  if (d > 0) return { kind: "overdue", label: `${plural(d, "day")} overdue`, daysVsDue: d };
  if (d === 0) return { kind: "open", label: "Due today", daysVsDue: d };
  return { kind: "open", label: `Due in ${plural(-d, "day")}`, daysVsDue: d };
}

/** Legend order + wording, shared by the pill dot and the legend under the selector. */
export const LOAN_BEHAVIOUR_LEGEND: ReadonlyArray<{ kind: LoanBehaviourKind; label: string }> = [
  { kind: "early", label: "early" },
  { kind: "onTime", label: "on time" },
  { kind: "late", label: "late" },
  { kind: "veryLate", label: "very late/settled" },
  { kind: "open", label: "open" },
  { kind: "overdue", label: "overdue" },
];

/** The CSS modifier (`globals.css` "loan behaviour pills" block) for a kind. */
export const LOAN_BEHAVIOUR_CLASS: Record<LoanBehaviourKind, string> = {
  early: "lb-early",
  onTime: "lb-ontime",
  late: "lb-late",
  veryLate: "lb-verylate",
  open: "lb-open",
  overdue: "lb-overdue",
};
