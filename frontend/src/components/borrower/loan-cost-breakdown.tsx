import * as React from "react";
import type { LoanCostBreakdown as LoanCostBreakdownData } from "@/lib/domain";
import { formatINR0, cn } from "@/lib/utils";

/**
 * Itemized loan cost breakdown for the salary-linked product: up-front 10%
 * processing fee + 18% GST, net disbursed, 1%/day interest, single-repayment
 * total. Shown before the borrower signs.
 */
export interface LoanCostBreakdownProps {
  breakdown: LoanCostBreakdownData;
  dueDate?: string;
  className?: string;
}

function Row({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="flex items-baseline justify-between py-3 border-b border-grey-200 last:border-0">
      <span className="text-sm text-muted">
        {label}
        {hint ? <span className="block text-xs text-muted/80">{hint}</span> : null}
      </span>
      <span className="font-serif font-semibold text-ink">{value}</span>
    </div>
  );
}

export function LoanCostBreakdown({ breakdown, dueDate, className }: LoanCostBreakdownProps) {
  const b = breakdown;
  return (
    <div className={cn("rounded border border-line bg-white p-6 shadow-sm", className)} data-testid="loan-cost-breakdown">
      <Row label="Loan amount" value={formatINR0(b.principal)} />
      <Row label="Processing fee" hint="10% of loan amount" value={`− ${formatINR0(b.processingFee)}`} />
      <Row label="GST on fee" hint="18% of processing fee" value={`− ${formatINR0(b.gstOnFee)}`} />
      <div className="flex items-baseline justify-between rounded bg-navy-tint px-3 py-3 my-2">
        <span className="text-sm font-semibold text-navy">Net amount to your bank</span>
        <span className="font-serif text-lg font-bold text-navy">{formatINR0(b.netDisbursed)}</span>
      </div>
      <Row label="Interest" hint={`1%/day × ${b.tenureDays} days`} value={`+ ${formatINR0(b.interest)}`} />
      <div className="mt-2 flex items-baseline justify-between rounded bg-navy px-4 py-4 text-white">
        <span className="text-sm font-semibold text-white/90">
          Total repayable
          {dueDate ? <span className="block text-xs font-normal text-white/70">Due on {dueDate}</span> : null}
        </span>
        <span className="font-serif text-2xl font-bold text-gold">{formatINR0(b.totalRepayable)}</span>
      </div>
    </div>
  );
}
