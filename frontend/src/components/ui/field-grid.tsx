import * as React from "react";
import { cn } from "@/lib/utils";

const COLS = {
  2: "grid-cols-2",
  3: "grid-cols-3",
  4: "grid-cols-4",
  6: "grid-cols-6",
  7: "grid-cols-7",
} as const;

export function FieldGrid({
  cols,
  children,
  className,
}: {
  cols: keyof typeof COLS;
  children: React.ReactNode;
  className?: string;
}) {
  return <div className={cn("grid gap-x-5 gap-y-3", COLS[cols], className)}>{children}</div>;
}

/** The only place money/outcome colours are encoded (design §1b). */
export const TONE_CLASS = {
  ink: "text-ink",
  navy: "text-navy font-bold",
  warning: "text-warning-800",
  error: "text-error-700 font-bold",
  success: "text-success-700",
  muted: "text-muted",
} as const;

export function Field({
  label,
  children,
  tone = "ink",
  mono,
  caption,
  className,
}: {
  label: React.ReactNode;
  children?: React.ReactNode;
  tone?: keyof typeof TONE_CLASS;
  mono?: boolean;
  caption?: React.ReactNode;
  className?: string;
}) {
  const empty = children == null || children === "";
  return (
    <div className={cn("min-w-0", className)}>
      <div className="text-[8.8px] font-semibold uppercase tracking-wide text-muted">{label}</div>
      <div className={cn("break-words text-[10.4px]", TONE_CLASS[tone], mono && "font-mono tabular-nums")}>
        {empty ? "—" : children}
      </div>
      {caption && <div className="text-[8.8px] text-muted">{caption}</div>}
    </div>
  );
}
