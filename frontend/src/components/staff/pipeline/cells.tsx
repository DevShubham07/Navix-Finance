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
 *
 * The two read differently on purpose, not just by tag: a request is the figure in ink with "req"
 * after it; a limit is muted, at normal weight, with "elig" in FRONT ("elig ₹25,000"), so a reader
 * scanning the column meets the qualifier before the number and never takes a limit for an ask.
 * Both set their own colour, so they read the same inside the callers' `font-semibold text-ink`
 * wrapper.
 *
 * Renders a fragment, so the alignment belongs to the caller: put it in a `<td className="num">`
 * (with `num` on the column's `<th>` too) so the amounts right-align as one tabular column.
 */
export function AmountCell({
  amountPaise,
  isRequested,
}: {
  amountPaise: number | null | undefined;
  isRequested: boolean;
}) {
  const figure = amountPaise != null ? paiseToINR(amountPaise) : "—";
  if (isRequested) {
    return (
      <>
        <span className="font-mono text-ink">{figure}</span>{" "}
        <span className="text-xs text-muted" title="Amount requested">
          req
        </span>
      </>
    );
  }
  return (
    <>
      <span className="text-xs font-normal text-muted" title="Eligible limit">
        elig
      </span>{" "}
      <span className="font-mono font-normal text-muted">{figure}</span>
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
