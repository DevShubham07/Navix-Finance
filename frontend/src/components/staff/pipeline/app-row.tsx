"use client";

import * as React from "react";
import { ArrowRight, Route, Info } from "lucide-react";
import { CreditBadge } from "@/components/staff/credit-badge";
import { ApplicationJourney } from "@/components/staff/application-journey";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import { ApplicationInfoDialog } from "@/components/staff/application-info-dialog";
import { LoanHistory } from "@/components/staff/pipeline/loan-history";
import { paiseToINR, type ApplicationView } from "@/lib/api/applications";
import { daysBetween } from "@/lib/calc/loan-math";
import { formatDate, formatDateTime } from "@/lib/utils";

function dpdDays(app: ApplicationView): number {
  if (!app.loanDueDate) return 0;
  return Math.max(0, daysBetween(new Date(`${app.loanDueDate}T00:00:00`), new Date()));
}

/**
 * One queue row, deliberately one line tall.
 *
 * The console is a triage grid. The row always carries the identifiers a staffer needs to recognise
 * a file at a glance — application id, customer name, mobile, customer id, and the loan number once
 * one exists — plus amount, stage and credit. Every *control* that needs typing or picking (assign
 * an executive, reject with a reason, mark pending, enter a transaction id) lives in
 * {@link ApplicationDetailDialog}, which `Open` and the id both raise and which already renders the
 * full stage cluster. Long values truncate with an ellipsis and keep the full text in `title`, so
 * nothing is lost and the table never scrolls sideways.
 */
export function AppRow({
  app,
  actions,
  withLoanHistory,
  showJourney = true,
}: {
  app: ApplicationView;
  actions: (app: ApplicationView) => React.ReactNode;
  withLoanHistory?: boolean;
  showJourney?: boolean;
}) {
  const [journeyOpen, setJourneyOpen] = React.useState(false);
  const [showDetail, setShowDetail] = React.useState(false);
  const [showInfo, setShowInfo] = React.useState(false);
  const dpd = dpdDays(app);
  // Disbursal-then-salary fallback — the same rule DisbursementFocus uses. Full values, no
  // masking: an explicit product decision (queues are screen-shared in practice; noted for
  // security review).
  const account = app.disbursalAccountNumber ?? app.salaryAccountNumber ?? null;
  const ifsc = app.disbursalIfsc ?? app.salaryIfsc ?? null;

  return (
    <>
      <tr>
        <td className="staff-sticky-identity">
          <button
            type="button"
            onClick={() => setShowDetail(true)}
            className="text-left font-serif font-semibold text-navy hover:underline"
            title={`Open application #${app.id}`}
          >
            #{app.id}
          </button>
        </td>
        <td className="font-mono text-muted">#{app.customerId}</td>
        <td className="whitespace-nowrap text-muted">
          {app.createdAt ? formatDateTime(app.createdAt) : "—"}
        </td>
        <td className="staff-cell font-semibold text-ink" title={app.customerName || undefined}>
          {app.customerName || "Name unavailable"}
        </td>
        <td className="font-mono text-muted">{app.customerMobile || "—"}</td>
        <td className="font-mono text-ink">{app.pan || "—"}</td>
        <td className="font-mono text-ink">{account || "—"}</td>
        <td className="font-mono text-ink">{ifsc || "—"}</td>
        <td className="font-mono text-muted">{app.loanId != null ? `#${app.loanId}` : "—"}</td>
        <td>
          <span className="font-semibold text-ink">
            {app.amountRequestedPaise != null
              ? paiseToINR(app.amountRequestedPaise)
              : app.eligibleLimitPaise != null
                ? paiseToINR(app.eligibleLimitPaise)
                : "—"}
          </span>{" "}
          <span
            className="text-xs text-muted"
            title={app.amountRequestedPaise != null ? "Amount requested" : "Eligible limit"}
          >
            {app.amountRequestedPaise != null ? "req" : "elig"}
          </span>
        </td>
        {/* Due date sits beside the amount because that's how a collections officer reads a row:
            how much, by when, and how late. An overdue loan also carries the extra days it has run
            past that date — and a loan that is overdue with no due date on file still shows the
            days, since "how late" is the only number that matters at that point. */}
        <td className="whitespace-nowrap">
          {app.loanDueDate ? (
            <span className={dpd > 0 ? "font-semibold text-error-700" : "text-ink"}>
              {formatDate(app.loanDueDate)}
            </span>
          ) : (
            <span className="text-muted">—</span>
          )}
          {dpd > 0 && (
            <span className="ml-1 text-xs font-semibold text-error-700" title="Days past due">
              +{dpd}d
            </span>
          )}
          {app.markedPendingAt && (
            <span
              className="ml-1 inline-flex rounded bg-warning-100 px-1.5 py-0.5 text-xs font-semibold text-warning-700"
              title={`Marked pending — ${app.pendingReason || "review required"}`}
            >
              Pending
            </span>
          )}
        </td>
        <td>
          <CreditBadge
            compact
            starRating={app.starRating}
            creditScore={app.creditScore}
            recommendation={app.recommendation}
          />
        </td>
        <td className="staff-sticky-actions">
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              onClick={() => setShowInfo(true)}
              className="btn btn-sm btn-outline btn-icon"
              aria-label="Quick summary"
              title="Quick summary"
            >
              <Info size={14} />
            </button>
            <button
              type="button"
              onClick={() => setShowDetail(true)}
              className="btn btn-sm btn-outline"
              title="Open the full application detail"
            >
              Open <ArrowRight size={14} />
            </button>
            {showJourney && (
              <button
                type="button"
                onClick={() => setJourneyOpen(true)}
                className="btn btn-sm btn-outline btn-icon"
                aria-label="View the full application journey"
                title="View the full application journey"
              >
                <Route size={14} />
              </button>
            )}
            {actions(app)}
          </div>

          {journeyOpen && (
            <ApplicationJourney
              applicationId={app.id}
              open={journeyOpen}
              onClose={() => setJourneyOpen(false)}
              onOpenDetail={() => {
                setJourneyOpen(false);
                setShowDetail(true);
              }}
            />
          )}
          {showDetail && (
            <ApplicationDetailDialog applicationId={app.id} onClose={() => setShowDetail(false)} />
          )}
          {showInfo && (
            <ApplicationInfoDialog applicationId={app.id} onClose={() => setShowInfo(false)} />
          )}
        </td>
      </tr>
      {withLoanHistory && (
        <tr>
          <td colSpan={13} className="bg-grey-50">
            <LoanHistory customerId={app.customerId} />
          </td>
        </tr>
      )}
    </>
  );
}
