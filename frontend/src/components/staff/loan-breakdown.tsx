import * as React from "react";
import {
  paiseToINR,
  type ApplicationView,
  type LoanView,
  type OutstandingView,
} from "@/lib/api/applications";
import { buildCostBreakdown, dueDateFromSalary, daysBetween } from "@/lib/calc/loan-math";

/**
 * The shared loan cost-breakdown `<dl>` — principal → fee/GST → net disbursed →
 * total repayable → accrued interest/penalty → paid → outstanding. Extracted
 * from `loan-detail-dialog.tsx` so the journey step popups, drawer and detail
 * page render identical figures.
 *
 * `outstanding` (the penalty/prepayment-aware `OutstandingView`) is optional:
 * when absent the itemized interest/penalty/paid rows are omitted and the
 * outstanding falls back to the loan's cached `outstandingPaise`.
 */
export function LoanBreakdown({
  loan,
  outstanding,
}: {
  loan: LoanView;
  outstanding?: OutstandingView | null;
}) {
  const out = outstanding;
  return (
    <dl className="grid grid-cols-2 gap-x-6 gap-y-1">
      <Row label="Principal" value={paiseToINR(loan.principalPaise)} />
      <Row label="Processing fee" value={paiseToINR(loan.processingFeePaise)} />
      <Row label="GST" value={paiseToINR(loan.gstPaise)} />
      <Row label="Net disbursed" value={paiseToINR(loan.netDisbursedPaise)} />
      <Row label="Total repayable" value={paiseToINR(loan.totalRepayablePaise)} />
      {out && <Row label="Interest accrued" value={paiseToINR(out.interestPaise ?? 0)} />}
      {out && (out.penaltyPaise ?? 0) > 0 && (
        <Row label="Late penalty" value={paiseToINR(out.penaltyPaise ?? 0)} />
      )}
      {out && <Row label="Paid (verified)" value={paiseToINR(out.verifiedPaise ?? 0)} />}
      <Row
        label="Outstanding"
        value={paiseToINR(out ? out.outstandingPaise : loan.outstandingPaise)}
        strong
      />
      {out?.settledAmountPaise != null && (
        <Row label="Settlement (full & final)" value={paiseToINR(out.settledAmountPaise)} />
      )}
    </dl>
  );
}

function Row({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="flex items-center justify-between">
      <dt className="text-muted">{label}</dt>
      <dd className={strong ? "font-semibold text-navy" : "text-ink"}>{value}</dd>
    </div>
  );
}

/**
 * The projected (pre-disbursement) cost breakdown for a requested amount — the
 * same `<dl>` the DISBURSEMENT/ACTIVE stages show with real figures, but computed
 * client-side from `buildCostBreakdown` over the salary-linked tenure estimate.
 * Shared by the credit step popup and the unified detail page's Cost card.
 *
 * Returns a muted note when no amount has been requested yet. The tenure is
 * estimated from the salary-credit day (falling back to a nominal 30-day cycle);
 * final figures are set at disbursal.
 */
export function ProjectedCostBreakdown({ app }: { app: ApplicationView }) {
  const amount = app.amountRequestedPaise;
  if (amount == null) {
    return <p className="text-muted">No amount has been requested yet.</p>;
  }

  // Estimate tenure from the salary-linked due date; fall back to a nominal cycle.
  let tenureDays = 30;
  if (app.salaryCreditDay != null) {
    const today = new Date();
    const due = dueDateFromSalary({ disbursedOn: today, salaryDay: app.salaryCreditDay });
    const d = daysBetween(today, due);
    if (d > 0) tenureDays = d;
  }
  // buildCostBreakdown is linear, so integer-paise in → integer-paise out.
  const b = buildCostBreakdown(amount, tenureDays);

  return (
    <>
      <dl className="grid grid-cols-2 gap-x-6 gap-y-1">
        <Row label="Principal" value={paiseToINR(b.principal)} />
        <Row label="Processing fee" value={paiseToINR(b.processingFee)} />
        <Row label="GST" value={paiseToINR(b.gstOnFee)} />
        <Row label="Net disbursed" value={paiseToINR(b.netDisbursed)} />
        <Row label={`Interest (${tenureDays}d)`} value={paiseToINR(b.interest)} />
        <Row label="Total repayable" value={paiseToINR(b.totalRepayable)} strong />
      </dl>
      <p className="mt-2 text-xs text-muted">
        Projection at the requested amount over an estimated {tenureDays}-day tenure. Final figures are
        set at disbursal.
      </p>
    </>
  );
}
