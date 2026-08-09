"use client";

/**
 * One application row for a status-backed queue (slimmed in Phase E; Open rewired to a
 * popup in Phase G).
 *
 * The row is now a scannable action line — id, customer identity, amount, status, the
 * credit badge, the stage's maker-checker action cluster, a "Journey" button (the
 * lifecycle drawer, for a quick peek) and an "Open →" button that raises the unified
 * {@link ApplicationDetailDialog} (the full deep-dive — identity, journey, verifications,
 * documents, past details, audit log, remarks — plus the stage action, all without leaving
 * the queue). The former inline expandables (CustomerReview, LoanHistory, the maker-checker
 * event trail) were removed: those details now live in the Journey drawer and the detail
 * popup, so a queue never loses its path to them.
 *
 * Documented exception: `withLoanHistory` re-attaches the inline loan-history
 * expandable for queues whose DECISION is about the borrower's loan history —
 * today only the reborrow-review queue (REVIEW_PENDING on /staff/applications),
 * where clearing/rejecting a returning borrower hinges on their past
 * overdue/closed loans and no other surface on that page carries it.
 */

import * as React from "react";
import { Route, ArrowRight } from "lucide-react";
import { CreditBadge } from "@/components/staff/credit-badge";
import { statusLabel, paiseToINR, type ApplicationView } from "@/lib/api/applications";
import { ApplicationJourney } from "@/components/staff/application-journey";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import { LoanHistory } from "@/components/staff/pipeline/loan-history";
import { daysBetween } from "@/lib/calc/loan-math";
import { formatDate } from "@/lib/utils";

/** Days past the loan's due date (0 when not yet due / no loan). */
function dpdDays(app: ApplicationView): number {
  if (!app.loanDueDate) return 0;
  return Math.max(0, daysBetween(new Date(`${app.loanDueDate}T00:00:00`), new Date()));
}

export function AppRow({
  app,
  actions,
  withLoanHistory,
}: {
  app: ApplicationView;
  actions: (app: ApplicationView) => React.ReactNode;
  /** Re-attach the inline loan-history expandable (reborrow-review queue only — see file doc). */
  withLoanHistory?: boolean;
}) {
  const [showJourney, setShowJourney] = React.useState(false);
  const [showDetail, setShowDetail] = React.useState(false);
  return (
    <li className="px-5 py-4">
      <div className="grid gap-3 lg:grid-cols-[minmax(18rem,1fr)_minmax(26rem,auto)] lg:items-center">
        <div className="min-w-0">
          <div className="flex flex-wrap items-baseline gap-x-2">
            <span className="font-serif text-base font-semibold text-navy">Application #{app.id}</span>
            <span className="text-xs font-normal text-muted">customer #{app.customerId}</span>
            {(app.customerName || app.customerMobile) && (
              <span className="max-w-[16rem] truncate text-xs font-normal text-muted">
                {app.customerName ? `· ${app.customerName}` : ""}
                {app.customerMobile ? ` · ${app.customerMobile}` : ""}
              </span>
            )}
          </div>
          <div className="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-muted">
            <span className="rounded-full bg-navy-tint px-2 py-0.5 font-semibold text-navy">{statusLabel(app.status)}</span>
            {app.markedPendingAt && (
              <span className="rounded-full bg-warning-100 px-2 py-0.5 font-semibold text-warning-700" title={app.pendingReason || "Pending review"}>
                Pending review
              </span>
            )}
            {/* The aggregate stays ACTIVE for the whole repayment window, so an "Active" pill on a
                90-days-late loan reads as a contradiction — flag the loan-level truth beside it. */}
            {dpdDays(app) > 0 && (
              <span className="rounded-full bg-error-100 px-2 py-0.5 font-semibold text-error-700">
                Overdue · {dpdDays(app)} DPD
              </span>
            )}
            {/* The borrower can only pick an amount after KYC approval (apply → NOT_APPLICABLE
                before that), so a KYC-stage row has no ask — show the eligible limit instead of
                a bare "Requested —", which reads as "asked for nothing". */}
            {app.amountRequestedPaise != null ? (
              <span>Requested {paiseToINR(app.amountRequestedPaise)}</span>
            ) : app.eligibleLimitPaise != null ? (
              <span>Limit {paiseToINR(app.eligibleLimitPaise)}</span>
            ) : null}
            {app.assignedExecutiveId != null && <span>· exec #{app.assignedExecutiveId}</span>}
            {app.loanId != null && <span>· loan #{app.loanId}</span>}
            {/* Due date. Overdue is derived from it, not from the status: OVERDUE is
                compute-on-read and never persisted (see `isLoanOverdue`). */}
            {app.loanDueDate && <span>· due {formatDate(app.loanDueDate)}</span>}
            <CreditBadge
              starRating={app.starRating}
              creditScore={app.creditScore}
              recommendation={app.recommendation}
            />
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={() => setShowDetail(true)}
            className="btn btn-sm btn-outline"
            title="Open the full application detail"
          >
            Open <ArrowRight size={14} />
          </button>
          <button
            onClick={() => setShowJourney(true)}
            className="btn btn-sm btn-outline"
            title="View the full application journey"
          >
            <Route size={14} /> Journey
          </button>
          {actions(app)}
        </div>
      </div>

      {withLoanHistory && (
        <div className="mt-3">
          <LoanHistory customerId={app.customerId} />
        </div>
      )}

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
    </li>
  );
}
