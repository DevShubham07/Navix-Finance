import * as React from "react";
import { cn } from "@/lib/utils";

/** A single label / value row, used in review and dashboard summaries. */
export function InfoRow({
  label,
  value,
  className,
}: {
  label: string;
  value: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex items-baseline justify-between gap-4 border-b border-grey-200 py-2.5 last:border-0", className)}>
      <span className="text-sm text-muted">{label}</span>
      <span className="text-right text-sm font-semibold text-ink">{value || "—"}</span>
    </div>
  );
}

/** A titled group of rows with an optional "Edit" link back to a wizard step. */
export function SummarySection({
  title,
  editHref,
  children,
}: {
  title: string;
  editHref?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-1 flex items-center justify-between">
        <h3 className="font-serif text-base font-semibold text-navy">{title}</h3>
        {editHref ? (
          <a href={editHref} className="text-sm font-semibold text-navy hover:text-navy-700">
            Edit
          </a>
        ) : null}
      </div>
      <div>{children}</div>
    </div>
  );
}
