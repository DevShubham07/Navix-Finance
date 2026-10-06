import * as React from "react";
import { cn } from "@/lib/utils";
import { formatApiError } from "@/lib/api/errors";

/**
 * The console's one query-failure state — message plus a way out.
 *
 * Replaces 170 occurrences of `<p className="text-error-700">…</p>` across 60 files, **none** of
 * which offered a retry: before this, no failed query anywhere in the console could be reloaded
 * without a full page refresh. `onRetry` is therefore the point of the component, not a nicety —
 * pass the query's `refetch`.
 *
 * `role="alert"` is deliberate and was missing from the original spec. Without it a screen-reader
 * user is never told that a register failed; they are simply shown nothing and left to conclude the
 * queue is empty. The whole app currently contains four `role="alert"`/`aria-live` instances, all
 * of them form errors, so this is the first non-form failure the console announces.
 *
 * `inTable` renders as a full-width table cell, for the same markup-validity reason as EmptyState.
 */
export interface ErrorStateProps {
  error: unknown;
  onRetry?: () => void;
  /** Overrides the message derived from `error`. */
  title?: string;
  className?: string;
  /** Render as a `<td colSpan>` row for use directly inside a register's `<tbody>`. */
  inTable?: number;
}

export function ErrorState({ error, onRetry, title, className, inTable }: ErrorStateProps) {
  const message = title ?? formatApiError(error, "Couldn't load this — please try again.");
  const body = (
    <div role="alert" className={cn("px-5 py-8 text-center", className)}>
      <p className="m-0 text-sm font-medium text-error-700">{message}</p>
      {onRetry && (
        <div className="mt-3 flex justify-center">
          <button type="button" className="btn btn-sm btn-outline" onClick={onRetry}>
            Try again
          </button>
        </div>
      )}
    </div>
  );

  if (inTable != null) {
    return (
      <tr>
        <td colSpan={inTable}>{body}</td>
      </tr>
    );
  }
  return body;
}
