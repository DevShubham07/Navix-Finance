import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * The console's one empty state.
 *
 * Replaces ~29 hand-rolled variants across 13 different wrapper class strings. The padding and
 * muted type are the most common of those (`px-5 py-8 text-center text-sm text-muted`), so adopting
 * this is visually a no-op on most pages.
 *
 * `hint` is where the distinction that matters goes: "nothing exists yet" and "your filters matched
 * nothing" are different facts and several pages currently render the first when they mean the
 * second. Pass a different `title`/`hint` for the filtered case — the component will not guess.
 *
 * `inTable` renders as a full-width table cell for use inside `<tbody>`, since a `<div>` there is
 * invalid markup the browser hoists out of the table.
 */
export interface EmptyStateProps {
  title: string;
  hint?: React.ReactNode;
  icon?: React.ReactNode;
  action?: React.ReactNode;
  className?: string;
  /** Render as a `<td colSpan>` row for use directly inside a register's `<tbody>`. */
  inTable?: number;
}

export function EmptyState({ title, hint, icon, action, className, inTable }: EmptyStateProps) {
  const body = (
    <div className={cn("px-5 py-8 text-center", className)}>
      {icon && <div className="mb-2 flex justify-center text-muted">{icon}</div>}
      <p className="m-0 text-sm font-medium text-ink">{title}</p>
      {hint && <p className="m-0 mt-1 text-xs text-muted">{hint}</p>}
      {action && <div className="mt-3 flex justify-center">{action}</div>}
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
