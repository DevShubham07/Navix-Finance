/**
 * Table cells shared between the live-applications queue and the customers roll-up.
 *
 * The two tables render different row types (`ApplicationView` is per-application, `CustomerSummary`
 * rolls several up), so they deliberately do NOT share a row component — but "how much" and "by
 * when" must read identically on both, right down to the req/elig tag and the overdue styling.
 * Only these two cells are genuinely identical; everything else stays local to its table.
 */

import { paiseToINR } from "@/lib/api/applications";
import { daysBetween } from "@/lib/calc/loan-math";
import { formatDate } from "@/lib/utils";

/** Days past due for a yyyy-mm-dd due date; 0 when absent or not yet due. */
export function dpdFor(dueDate: string | null | undefined): number {
  if (!dueDate) return 0;
  return Math.max(0, daysBetween(new Date(`${dueDate}T00:00:00`), new Date()));
}

/**
 * Amount plus the tag that says what it IS — a drawn request or the eligible limit. The tag is not
 * decoration: without it a limit reads as a request, which changes how a file is triaged.
 */
export function AmountCell({
  amountPaise,
  isRequested,
}: {
  amountPaise: number | null | undefined;
  isRequested: boolean;
}) {
  return (
    <>
      <span className="font-mono">{amountPaise != null ? paiseToINR(amountPaise) : "—"}</span>{" "}
      <span className="text-xs text-muted" title={isRequested ? "Amount requested" : "Eligible limit"}>
        {isRequested ? "req" : "elig"}
      </span>
    </>
  );
}

/**
 * Due date, the days it has run past that date, and the pending flag.
 *
 * An overdue loan with no due date on file still shows the days — "how late" is the only number
 * that matters at that point.
 */
export function DueCell({
  dueDate,
  markedPendingAt,
  pendingReason,
}: {
  dueDate: string | null | undefined;
  markedPendingAt?: string | null;
  pendingReason?: string | null;
}) {
  const dpd = dpdFor(dueDate);
  return (
    <>
      {dueDate ? (
        <span className={dpd > 0 ? "font-semibold text-error-700" : "text-ink"}>
          {formatDate(dueDate)}
        </span>
      ) : (
        <span className="text-muted">—</span>
      )}
      {dpd > 0 && (
        <span className="ml-1 text-xs font-semibold text-error-700" title="Days past due">
          +{dpd}d
        </span>
      )}
      {markedPendingAt && (
        <span
          className="ml-1 inline-flex rounded bg-warning-100 px-1.5 py-0.5 text-xs font-semibold text-warning-700"
          title={`Marked pending — ${pendingReason || "review required"}`}
        >
          Pending
        </span>
      )}
    </>
  );
}
