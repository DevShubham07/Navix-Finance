import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * The console's one loading placeholder.
 *
 * Replaces 51 hand-rolled `animate-pulse` blocks across 31 files, which used four different fills
 * and — more importantly — four different *shapes*, so a section's skeleton rarely resembled the
 * content that replaced it. Each variant here keeps the shape of what it stands in for:
 *
 *  - `line`  — a text run. `rows` stacks several, the last one short, the way a paragraph ends.
 *  - `stat`  — a StatCard: label line over a big figure, inside the card's own border and padding.
 *  - `row`   — a card/list row.
 *  - `table` — `rows` zebra-striped table rows at the register's `8px 14px` cell rhythm, so the
 *              header does not jump when real rows arrive.
 *
 * Fills come from the console's own surfaces (`--grey-100` / `--line`), not Tailwind greys, so a
 * skeleton never reads as a different colour temperature from the table it covers.
 *
 * `animate-pulse` is Tailwind's; the `.skeleton` class exists so the `prefers-reduced-motion` block
 * in globals.css can switch the animation off without reaching into component markup.
 *
 * Render this on `isLoading` (first load) only, never on `isFetching` — a background refetch
 * should spin the header's refresh affordance and leave the rows in place. With
 * `placeholderData: keepPreviousData` the distinction is what keeps a register from blanking on
 * every filter change.
 */
export interface SkeletonProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: "line" | "stat" | "row" | "table";
  /** Number of lines (`line`) or rows (`table`). Ignored by `stat` and `row`. */
  rows?: number;
  /** Column count for `table`, so the placeholder matches the register's width. */
  cols?: number;
}

const BAR = "skeleton animate-pulse rounded bg-grey-100";

export function Skeleton({ variant = "line", rows = 3, cols = 5, className, ...props }: SkeletonProps) {
  if (variant === "stat") {
    return (
      <div
        className={cn("rounded border border-line bg-white p-4", className)}
        aria-hidden="true"
        {...props}
      >
        <div className={cn(BAR, "h-2.5 w-20")} />
        <div className={cn(BAR, "mt-3 h-6 w-28")} />
      </div>
    );
  }

  if (variant === "row") {
    return (
      <div
        className={cn("rounded border border-line bg-white p-4", className)}
        aria-hidden="true"
        {...props}
      >
        <div className={cn(BAR, "h-3 w-1/3")} />
        <div className={cn(BAR, "mt-2.5 h-2.5 w-2/3")} />
      </div>
    );
  }

  if (variant === "table") {
    return (
      <div className={cn("w-full", className)} aria-hidden="true" {...props}>
        {Array.from({ length: rows }).map((_, r) => (
          <div
            key={r}
            className={cn(
              "flex items-center gap-4 border-b border-line px-[14px] py-2",
              r % 2 === 1 && "bg-grey-100",
            )}
          >
            {Array.from({ length: cols }).map((_, c) => (
              <div
                key={c}
                className={cn(BAR, "h-2.5", c === 0 ? "w-10" : c === 1 ? "flex-[2]" : "flex-1")}
              />
            ))}
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className={cn("w-full", className)} aria-hidden="true" {...props}>
      {Array.from({ length: rows }).map((_, i) => (
        <div
          key={i}
          className={cn(BAR, "h-2.5", i > 0 && "mt-2", i === rows - 1 && rows > 1 && "w-2/3")}
        />
      ))}
    </div>
  );
}
