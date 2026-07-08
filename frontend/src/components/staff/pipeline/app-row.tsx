"use client";

/**
 * One application row for a status-backed queue (slimmed in Phase E).
 *
 * The row is now a scannable action line — id, customer, amount, status, the
 * credit badge, the stage's maker-checker action cluster, a "Journey" button (the
 * lifecycle drawer) and an "Open →" deep-link to the unified detail page. The
 * former inline expandables (CustomerReview, LoanHistory, the maker-checker event
 * trail) were removed: those details now live in the Journey drawer and on the
 * detail page, so a queue never loses its path to them.
 *
 * Documented exception: `withLoanHistory` re-attaches the inline loan-history
 * expandable for queues whose DECISION is about the borrower's loan history —
 * today only the reborrow-review queue (REVIEW_PENDING on /staff/kyc-review),
 * where clearing/rejecting a returning borrower hinges on their past
 * overdue/closed loans and no other surface on that page carries it.
 */

import * as React from "react";
import Link from "next/link";
import { Route, ArrowRight } from "lucide-react";
import { CreditBadge } from "@/components/staff/credit-badge";
import { statusLabel, paiseToINR, type ApplicationView } from "@/lib/api/applications";
import { ApplicationJourney } from "@/components/staff/application-journey";
import { LoanHistory } from "@/components/staff/pipeline/loan-history";

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
  return (
    <li className="px-5 py-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="font-serif text-base font-semibold text-navy">
            Application #{app.id}
            <span className="ml-2 align-middle text-xs font-normal text-muted">customer #{app.customerId}</span>
          </div>
          <div className="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-muted">
            <span className="rounded-full bg-navy-tint px-2 py-0.5 font-semibold text-navy">{statusLabel(app.status)}</span>
            <span>Requested {paiseToINR(app.amountRequestedPaise)}</span>
            {app.assignedExecutiveId != null && <span>· exec #{app.assignedExecutiveId}</span>}
            {app.loanId != null && <span>· loan #{app.loanId}</span>}
            <CreditBadge
              starRating={app.starRating}
              creditScore={app.creditScore}
              recommendation={app.recommendation}
            />
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Link
            href={`/staff/credit/${app.id}`}
            className="btn btn-sm btn-outline"
            title="Open the full application detail"
          >
            Open <ArrowRight size={14} />
          </Link>
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
        />
      )}
    </li>
  );
}
