"use client";

import * as React from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, RefreshCw } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { CreditBadge } from "@/components/staff/credit-badge";
import { staffApi, statusLabel, paiseToINR, type ApplicationView } from "@/lib/api/applications";
import {
  CustomerReview,
  KycActions,
  AssignActions,
  ExecActions,
  HeadActions,
  DisbursementActions,
  AccountantActions,
  errMessage,
} from "@/components/staff/live-pipeline";

/** The action available for an application's current pipeline stage. */
function actionFor(app: ApplicationView): React.ReactNode {
  switch (app.status) {
    case "KYC_PENDING":
      return <KycActions app={app} />;
    case "KYC_APPROVED":
      return app.amountRequestedPaise != null ? <AssignActions app={app} /> : null;
    case "CREDIT_EXEC_PENDING":
      return <ExecActions app={app} />;
    case "CREDIT_HEAD_PENDING":
      return <HeadActions app={app} />;
    case "DISBURSEMENT_PENDING":
    case "DISBURSEMENT_FAILED":
      return <DisbursementActions app={app} />;
    case "ACCOUNTANT_PENDING":
      return <AccountantActions app={app} />;
    default:
      return null;
  }
}

/** Single-application detail + the maker-checker action for its current stage (live). */
export default function CreditReviewPage() {
  const { applicationId } = useParams<{ applicationId: string }>();
  const id = Number.parseInt(applicationId, 10);

  const q = useQuery({
    queryKey: ["staff-application", id],
    queryFn: () => staffApi.get(id),
    enabled: Number.isFinite(id),
    refetchInterval: 8000,
  });

  // get(id) is borrower-safe (no credit fields); the staff-only headline comes from the brief endpoint.
  const briefQ = useQuery({
    queryKey: ["credit-brief", id],
    queryFn: () => staffApi.creditBrief(id),
    enabled: Number.isFinite(id),
  });

  const app = q.data;
  const action = app ? actionFor(app) : null;

  return (
    <div>
      <Link href="/staff/credit/queue" className="mb-3 inline-flex items-center gap-1 text-sm text-muted hover:text-navy">
        <ArrowLeft size={15} /> Credit queue
      </Link>
      <PageHeader title={`Application #${Number.isFinite(id) ? id : "—"}`} subtitle="Review the customer and act on the current pipeline stage.">
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          <RefreshCw size={13} /> Refresh
        </button>
      </PageHeader>

      {q.isLoading ? (
        <div className="h-40 animate-pulse rounded border border-line bg-white" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : !app ? (
        <p className="text-sm text-muted">Application #{applicationId} not found.</p>
      ) : (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3 rounded border border-line bg-white p-5 shadow-sm">
            <div className="min-w-0">
              <div className="font-serif text-lg font-semibold text-navy">Application #{app.id}</div>
              <div className="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-muted">
                <span className="rounded-full bg-navy-tint px-2 py-0.5 font-semibold text-navy">{statusLabel(app.status)}</span>
                <span>customer #{app.customerId}</span>
                <span>· requested {paiseToINR(app.amountRequestedPaise)}</span>
                {app.assignedExecutiveId != null && <span>· exec #{app.assignedExecutiveId}</span>}
                {app.loanId != null && <span>· loan #{app.loanId}</span>}
                {briefQ.data?.available && (
                  <CreditBadge
                    starRating={briefQ.data.starRating}
                    creditScore={briefQ.data.creditScore}
                    recommendation={briefQ.data.recommendation}
                  />
                )}
              </div>
            </div>
            {action && <div className="flex items-center gap-2">{action}</div>}
          </div>

          {!action && (
            <p className="text-sm text-muted">No maker-checker action is available at the {statusLabel(app.status)} stage.</p>
          )}

          <CustomerReview applicationId={app.id} />
        </div>
      )}
    </div>
  );
}
