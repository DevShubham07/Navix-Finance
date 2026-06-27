import * as React from "react";
import { Check } from "lucide-react";
import { cn } from "@/lib/utils";
import type { BorrowerStatus } from "@/lib/domain/borrower";

/**
 * Vertical timeline of the customer-facing status journey (product flow §10):
 * Applied → Under review → Approved → Docs signed → Bank verified →
 * Money on the way → Active → Repaid.
 */
export interface LoanStatusTrackerProps {
  status: BorrowerStatus;
  className?: string;
}

const STAGES: Array<{ key: BorrowerStatus; label: string; sub: string }> = [
  { key: "APPLIED", label: "Applied", sub: "Application submitted" },
  { key: "UNDER_REVIEW", label: "Under review", sub: "Credit team is reviewing" },
  { key: "APPROVED", label: "Approved", sub: "Loan offer ready" },
  { key: "DOCS_SIGNED", label: "Documents signed", sub: "Agreement, sanction letter & KFS e-signed" },
  { key: "BANK_VERIFIED", label: "Bank verified", sub: "Penny-drop name match" },
  { key: "DISBURSING", label: "Money on the way", sub: "Partner NBFC transferring funds" },
  { key: "ACTIVE", label: "Active", sub: "Due on your salary day" },
  { key: "REPAID", label: "Repaid & closed", sub: "Loan fully settled" },
];

const ORDER = STAGES.map((s) => s.key);

export function LoanStatusTracker({ status, className }: LoanStatusTrackerProps) {
  const currentIndex = status === "OVERDUE" ? ORDER.indexOf("ACTIVE") : ORDER.indexOf(status);
  return (
    <ol className={cn("relative ml-1", className)} data-testid="loan-status-tracker">
      {STAGES.map((stage, i) => {
        const done = i < currentIndex;
        const active = i === currentIndex;
        return (
          <li key={stage.key} className="relative flex gap-4 pb-6 last:pb-0">
            {i < STAGES.length - 1 && (
              <span className={cn("absolute left-[13px] top-7 h-[calc(100%-1rem)] w-0.5", done ? "bg-success-600" : "bg-line")} />
            )}
            <span
              className={cn(
                "z-10 mt-0.5 flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-full text-xs font-bold",
                done && "bg-success-600 text-white",
                active && "bg-navy text-white ring-4 ring-navy-tint",
                !done && !active && "border-2 border-line bg-white text-muted",
              )}
            >
              {done ? <Check size={14} strokeWidth={3} /> : i + 1}
            </span>
            <div className={cn("pt-0.5", !done && !active && "opacity-60")}>
              <div className={cn("font-semibold", active ? "text-navy" : "text-ink")}>{stage.label}</div>
              <div className="text-sm text-muted">{stage.sub}</div>
            </div>
          </li>
        );
      })}
    </ol>
  );
}
