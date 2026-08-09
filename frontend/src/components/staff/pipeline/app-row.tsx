"use client";

import * as React from "react";
import { ArrowRight, Route } from "lucide-react";
import { CreditBadge } from "@/components/staff/credit-badge";
import { ApplicationJourney } from "@/components/staff/application-journey";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import { LoanHistory } from "@/components/staff/pipeline/loan-history";
import { statusLabel, paiseToINR, type ApplicationView } from "@/lib/api/applications";
import { daysBetween } from "@/lib/calc/loan-math";
import { formatDate } from "@/lib/utils";

function dpdDays(app: ApplicationView): number {
  if (!app.loanDueDate) return 0;
  return Math.max(0, daysBetween(new Date(`${app.loanDueDate}T00:00:00`), new Date()));
}

/** A semantic staff-table row with identity and actions kept visible while horizontally scrolling. */
export function AppRow({
  app,
  actions,
  withLoanHistory,
}: {
  app: ApplicationView;
  actions: (app: ApplicationView) => React.ReactNode;
  withLoanHistory?: boolean;
}) {
  const [showJourney, setShowJourney] = React.useState(false);
  const [showDetail, setShowDetail] = React.useState(false);
  const dpd = dpdDays(app);

  return (
    <>
      <tr>
        <td className="staff-sticky-identity">
          <button
            type="button"
            onClick={() => setShowDetail(true)}
            className="text-left font-serif font-semibold text-navy hover:underline"
          >
            #{app.id}
          </button>
          <div className="mt-1 text-xs text-muted">Customer #{app.customerId}</div>
        </td>
        <td>
          <div className="font-semibold text-ink">{app.customerName || "Name unavailable"}</div>
          <div className="mt-1 font-mono text-xs text-muted">{app.customerMobile || "Mobile unavailable"}</div>
        </td>
        <td>
          <div className="font-semibold text-ink">
            {app.amountRequestedPaise != null
              ? paiseToINR(app.amountRequestedPaise)
              : app.eligibleLimitPaise != null
                ? paiseToINR(app.eligibleLimitPaise)
                : "—"}
          </div>
          <div className="mt-1 text-xs text-muted">
            {app.amountRequestedPaise != null ? "Requested" : "Eligible limit"}
          </div>
          {app.sanctionedAmountPaise != null && (
            <div className="mt-1 text-xs text-muted">Sanctioned {paiseToINR(app.sanctionedAmountPaise)}</div>
          )}
        </td>
        <td>
          <span className="inline-flex rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">
            {statusLabel(app.status)}
          </span>
          {app.markedPendingAt && (
            <div
              className="mt-2 max-w-[14rem] rounded bg-warning-100 px-2 py-1 text-xs font-semibold text-warning-700"
              title={app.pendingReason || "Pending review"}
            >
              Pending: {app.pendingReason || "Review required"}
            </div>
          )}
          {dpd > 0 && <div className="mt-2 text-xs font-semibold text-error-700">Overdue · {dpd} DPD</div>}
        </td>
        <td>
          <CreditBadge
            starRating={app.starRating}
            creditScore={app.creditScore}
            recommendation={app.recommendation}
          />
        </td>
        <td className="text-xs text-muted">
          <div>{app.assignedExecutiveId != null ? `Executive #${app.assignedExecutiveId}` : "Unassigned"}</div>
          <div className="mt-1">{app.loanId != null ? `Loan #${app.loanId}` : "Loan not created"}</div>
          {app.loanDueDate && <div className="mt-1">Due {formatDate(app.loanDueDate)}</div>}
        </td>
        <td className="staff-sticky-actions">
          <div className="flex min-w-max flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => setShowDetail(true)}
              className="btn btn-sm btn-outline"
              title="Open the full application detail"
            >
              Open <ArrowRight size={14} />
            </button>
            <button
              type="button"
              onClick={() => setShowJourney(true)}
              className="btn btn-sm btn-outline"
              title="View the full application journey"
            >
              <Route size={14} /> Journey
            </button>
            {actions(app)}
          </div>

          {showJourney && (
            <ApplicationJourney
              applicationId={app.id}
              open={showJourney}
              onClose={() => setShowJourney(false)}
              onOpenDetail={() => {
                setShowJourney(false);
                setShowDetail(true);
              }}
            />
          )}
          {showDetail && (
            <ApplicationDetailDialog applicationId={app.id} onClose={() => setShowDetail(false)} />
          )}
        </td>
      </tr>
      {withLoanHistory && (
        <tr>
          <td colSpan={7} className="bg-grey-50">
            <LoanHistory customerId={app.customerId} />
          </td>
        </tr>
      )}
    </>
  );
}
