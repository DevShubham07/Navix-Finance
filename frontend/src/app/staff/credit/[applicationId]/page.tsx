"use client";

import * as React from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, RefreshCw, Route, Banknote, Zap, Loader2, ChevronRight, ScrollText } from "lucide-react";
import { EventTimeline } from "@/components/staff/event-timeline";
import { PageHeader } from "@/components/staff/staff-ui";
import { CreditBadge } from "@/components/staff/credit-badge";
import { ApplicationJourney } from "@/components/staff/application-journey";
import { JourneyStepper } from "@/components/staff/journey-stepper";
import { StageDetailDialog } from "@/components/staff/stage-detail-dialog";
import { LoanBreakdown, ProjectedCostBreakdown } from "@/components/staff/loan-breakdown";
import { LoanHistory } from "@/components/staff/pipeline/loan-history";
import { deriveJourney, type JourneyStage } from "@/lib/domain/journey";
import { staffApi, statusLabel, paiseToINR, type ApplicationView } from "@/lib/api/applications";
import {
  CustomerReview,
  KycActions,
  ReviewActions,
  AssignActions,
  ExecActions,
  HeadActions,
  DisbursementActions,
  AccountantActions,
  errMessage,
} from "@/components/staff/live-pipeline";
import { formatDate } from "@/lib/utils";

const todayISO = () => new Date().toISOString().slice(0, 10);

/** The action available for an application's current pipeline stage. */
function actionFor(app: ApplicationView): React.ReactNode {
  switch (app.status) {
    case "KYC_PENDING":
      return <KycActions app={app} />;
    case "REVIEW_PENDING":
      // Reborrow review for a returning borrower flagged for a past overdue.
      return <ReviewActions app={app} />;
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

/**
 * The loan's real cost breakdown once a loan is minted (penalty/prepayment-aware
 * outstanding for a live loan), else the pre-disbursement projection. Uses the
 * same query keys/fns as the journey step popup so the figures stay consistent.
 */
function CostCard({ app }: { app: ApplicationView }) {
  const hasLoan = app.loanId != null;
  const loanQ = useQuery({
    queryKey: ["staff-loan", app.loanId],
    queryFn: () => staffApi.loan(app.loanId as number),
    enabled: hasLoan,
    retry: false,
  });
  const outQ = useQuery({
    queryKey: ["staff-loan-out", app.loanId, todayISO()],
    queryFn: () => staffApi.outstanding(app.loanId as number, todayISO()),
    enabled: hasLoan,
    retry: false,
  });
  const loan = loanQ.data;

  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <h2 className="mb-3 flex items-center gap-2 font-serif text-base font-semibold text-navy">
        <Banknote size={16} aria-hidden /> Cost
        {(loanQ.isLoading || outQ.isLoading) && <Loader2 size={14} className="animate-spin text-muted" />}
      </h2>
      {hasLoan ? (
        loan ? (
          <>
            {loan.dueDate && <p className="mb-2 text-sm text-muted">Due {formatDate(loan.dueDate)}</p>}
            <LoanBreakdown loan={loan} outstanding={outQ.data} />
          </>
        ) : loanQ.error ? (
          <p className="text-sm text-error-700">{errMessage(loanQ.error)}</p>
        ) : (
          <p className="text-sm text-muted">Loading…</p>
        )
      ) : (
        <ProjectedCostBreakdown app={app} />
      )}
    </div>
  );
}

/** Single-application detail: summary → journey → action context → cost → loan history → customer review. */
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

  // Events back the always-visible inline Journey stepper (same key/fn as the drawer & the pipeline).
  const eventsQ = useQuery({
    queryKey: ["staff-events", id],
    queryFn: () => staffApi.events(id),
    enabled: Number.isFinite(id),
  });

  const app = q.data;
  const action = app ? actionFor(app) : null;
  const [journeyOpen, setJourneyOpen] = React.useState(false);
  const [openStage, setOpenStage] = React.useState<JourneyStage | null>(null);

  const events = eventsQ.data ?? [];
  const journey = app ? deriveJourney(app, events) : null;
  // The current stage = the last stage that isn't still "upcoming".
  const activeIndex = journey
    ? journey.stages.reduce((acc, s, i) => (s.state !== "upcoming" ? i : acc), 0)
    : 0;

  return (
    <div>
      <Link href="/staff/credit/queue" className="mb-3 inline-flex items-center gap-1 text-sm text-muted hover:text-navy">
        <ArrowLeft size={15} /> Credit queue
      </Link>
      <PageHeader title={`Application #${Number.isFinite(id) ? id : "—"}`} subtitle="Review the customer and act on the current pipeline stage.">
        <button
          onClick={() => {
            q.refetch();
            eventsQ.refetch(); // the inline stepper + audit log are events-driven
          }}
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
          {/* 1 — Summary + the current stage's maker-checker action */}
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
            <div className="flex items-center gap-2">
              <button
                onClick={() => setJourneyOpen(true)}
                className="btn btn-sm btn-outline"
                title="Open the compact journey drawer"
              >
                <Route size={14} /> Journey
              </button>
              {action}
            </div>
          </div>

          {/* 2 — Journey (always-visible inline stepper; nodes open the step popup) */}
          <div className="rounded border border-line bg-white p-5 shadow-sm">
            <h2 className="mb-3 flex items-center gap-2 font-serif text-base font-semibold text-navy">
              <Route size={16} aria-hidden /> Journey
              {journey?.fastTrack && (
                <span className="inline-flex items-center gap-1 rounded-full bg-gold-50 px-2 py-0.5 text-xs font-semibold text-gold-dark">
                  <Zap size={12} /> Fast-track
                </span>
              )}
            </h2>
            {eventsQ.isLoading ? (
              <p className="text-sm text-muted">Loading journey…</p>
            ) : journey ? (
              <JourneyStepper stages={journey.stages} activeIndex={activeIndex} onStageClick={setOpenStage} />
            ) : null}
          </div>

          {/* 3 — Action context */}
          {!action && (
            <p className="text-sm text-muted">No maker-checker action is available at the {statusLabel(app.status)} stage.</p>
          )}

          {/* 4 — Cost */}
          <CostCard app={app} />

          {/* 5 — Loan history (self-toggling; every role's path to the borrower's past loans) */}
          <LoanHistory customerId={app.customerId} />

          {/* 6 — Customer review */}
          <CustomerReview applicationId={app.id} />

          {/* 7 — Full chronological audit log (IA layer 6; collapsed — the deepest layer) */}
          <details className="group rounded border border-line bg-white shadow-sm">
            <summary className="flex cursor-pointer items-center gap-2 px-5 py-4 [&::-webkit-details-marker]:hidden">
              <ChevronRight size={15} className="text-navy transition-transform group-open:rotate-90" />
              <ScrollText size={16} className="text-navy" aria-hidden />
              <h2 className="mb-0 font-serif text-base font-semibold text-navy">Audit log</h2>
              <span className="text-xs text-muted">every action, actor and timestamp</span>
            </summary>
            <div className="border-t border-line p-5">
              {events.length === 0 ? (
                <p className="text-sm text-muted">No events recorded yet.</p>
              ) : (
                <EventTimeline events={events} />
              )}
            </div>
          </details>

          {/* Compact journey drawer (summary button) + the shared per-step popup */}
          <ApplicationJourney
            applicationId={app.id}
            open={journeyOpen}
            onClose={() => setJourneyOpen(false)}
          />
          {openStage && (
            <StageDetailDialog
              applicationId={app.id}
              app={app}
              stage={openStage}
              allEvents={events}
              open
              onClose={() => setOpenStage(null)}
            />
          )}
        </div>
      )}
    </div>
  );
}
