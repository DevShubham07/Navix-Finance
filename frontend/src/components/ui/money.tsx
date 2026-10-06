import * as React from "react";
import { cn } from "@/lib/utils";
import { paiseToINR } from "@/lib/api/applications";

/**
 * Integer paise rendered as a right-aligned, tabular, non-wrapping figure.
 *
 * All money in this system is integer paise (CLAUDE.md §9/§12), and `paiseToINR` is the only
 * formatter — this component exists so the *alignment* is not re-decided per call site. Today money
 * cells are `font-mono` but inherit `text-align: left` from `.staff-data-table td`, so a column of
 * amounts does not line up at the decimal and cannot be scanned for magnitude.
 *
 * In a register, prefer `<td className="num">` (the CSS does the same job for the whole column,
 * including its header) and use this for money outside tables — cards, detail panels, dialog
 * summaries — or where a `<td>` holds more than the figure.
 *
 * `null`/`undefined` renders `paiseToINR`'s em dash, never `₹0`: an unmeasured value must not read
 * as a measured zero.
 */
export interface MoneyProps extends React.HTMLAttributes<HTMLSpanElement> {
  paise: number | null | undefined;
  /** `false` leaves horizontal alignment to the parent (e.g. inside a flex row). */
  align?: boolean;
}

export function Money({ paise, align = true, className, ...props }: MoneyProps) {
  return (
    <span
      className={cn("font-mono whitespace-nowrap tabular-nums", align && "block text-right", className)}
      {...props}
    >
      {paiseToINR(paise)}
    </span>
  );
}
