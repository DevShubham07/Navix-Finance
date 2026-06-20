import * as React from "react";
import type { LoanCostBreakdown as LoanCostBreakdownData } from "@/lib/domain";

// TODO: format currency with locale + tooltips explaining each charge.
// Shows up-front 10% processing fee + 18% GST on the fee, net disbursed,
// 1%/day interest accrual, and projected single-repayment total.
export interface LoanCostBreakdownProps {
  breakdown: LoanCostBreakdownData;
  className?: string;
}

export function LoanCostBreakdown({ breakdown, className }: LoanCostBreakdownProps) {
  return (
    <div className={className} data-testid="loan-cost-breakdown">
      {/* TODO: render rows for fee, GST on fee, net disbursed, interest, total repayable */}
      <pre>{JSON.stringify(breakdown, null, 2)}</pre>
    </div>
  );
}
