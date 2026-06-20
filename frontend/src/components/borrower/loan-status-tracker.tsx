import * as React from "react";
import type { LoanStatus } from "@/lib/domain";

// TODO: visualize the loan lifecycle (applied -> review -> approved -> disbursed -> repaid).
export interface LoanStatusTrackerProps {
  status: LoanStatus;
  className?: string;
}

export function LoanStatusTracker({ status, className }: LoanStatusTrackerProps) {
  return (
    <div className={className} data-testid="loan-status-tracker">
      {/* TODO: map status to a stepper with timestamps */}
      <span>Current status: {status}</span>
    </div>
  );
}
