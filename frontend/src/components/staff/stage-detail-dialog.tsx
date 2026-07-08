"use client";

/**
 * Per-step detail popup for the application Journey (staff-only).
 *
 * Opened by clicking a stage node in {@link ApplicationJourney}. Built on the
 * shared {@link Dialog} primitive and rendered as a sibling of the Drawer (not a
 * descendant of its transformed panel, so its fixed overlay covers the viewport
 * and stacks above the drawer). Focus moves into the dialog on open and is
 * restored to the triggering step node on close via {@link useFocusTrap}; a
 * capture-phase Escape handler closes only this popup (leaving the drawer open),
 * so Escape closes the popup first, then the drawer on a second press.
 *
 * Every stage-specific query is lazy (the dialog only mounts while open) and
 * never polls. Money is integer paise (via `paiseToINR`); dates via the shared
 * formatters. This surface renders staff-only credit detail and must never be
 * imported from the borrower route tree.
 */

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import {
  X,
  Loader2,
  FileText,
  ExternalLink,
  Download,
  Check,
  Clock,
  CircleDot,
  AlertTriangle,
  XCircle,
  Ban,
  MinusCircle,
  Circle,
  Zap,
  type LucideIcon,
} from "lucide-react";
import { Dialog, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { LoanBreakdown, ProjectedCostBreakdown } from "@/components/staff/loan-breakdown";
import { EventTimeline } from "@/components/staff/event-timeline";
import { useFocusTrap } from "@/hooks/use-focus-trap";
import {
  staffApi,
  paiseToINR,
  openDocument,
  ApplicationApiError,
  type ApplicationView,
  type EventView,
  type DocumentView,
  type PaymentView,
  type CheckStatus,
  type StepResult,
} from "@/lib/api/applications";
import {
  STAGE_ORDER,
  STAGE_LABELS,
  type JourneyStage,
  type JourneyStageKey,
  type JourneyStageState,
} from "@/lib/domain/journey";
import { formatDate } from "@/lib/utils";

const todayISO = () => new Date().toISOString().slice(0, 10);

// ---------------------------------------------------------------------------
// Stage-state → outcome pill (text label + icon + tone; never colour-only)
// ---------------------------------------------------------------------------

const OUTCOME_PILL: Record<
  JourneyStageState,
  { Icon: LucideIcon; label: string; className: string }
> = {
  upcoming: { Icon: Circle, label: "Not started", className: "bg-grey-100 text-muted" },
  pending: { Icon: Clock, label: "Pending", className: "bg-warning-100 text-warning-800" },
  in_progress: { Icon: CircleDot, label: "In progress", className: "bg-info-100 text-info-700" },
  done: { Icon: Check, label: "Completed", className: "bg-success-100 text-success-700" },
  skipped: { Icon: MinusCircle, label: "Skipped — pre-approved", className: "bg-grey-100 text-muted" },
  active: { Icon: CircleDot, label: "Active", className: "bg-navy-tint text-navy" },
  overdue: { Icon: AlertTriangle, label: "Overdue", className: "bg-warning-100 text-warning-800" },
  defaulted: { Icon: AlertTriangle, label: "Defaulted", className: "bg-error-100 text-error-700" },
  failed: { Icon: XCircle, label: "Disbursement failed", className: "bg-error-100 text-error-700" },
  rejected: { Icon: XCircle, label: "Rejected", className: "bg-error-100 text-error-700" },
  cancelled: { Icon: Ban, label: "Cancelled", className: "bg-grey-100 text-muted" },
  written_off: { Icon: Ban, label: "Written off", className: "bg-error-100 text-error-700" },
};

/** One-sentence "what happens here + who acts" per macro-stage (used for upcoming stages). */
const STAGE_BLURB: Record<JourneyStageKey, string> = {
  STARTED: "The borrower creates the application and submits their KYC details.",
  KYC: "A KYC approver verifies identity, income and documents, then approves or rejects.",
  CREDIT_REVIEW:
    "A credit executive recommends and the credit head gives final approval (separation of duties).",
  DISBURSEMENT:
    "The disbursement head releases the funds and an accountant confirms the bank transfer.",
  ACTIVE_REPAYMENT:
    "The loan is live; the borrower repays on their salary day and an accountant verifies the payment.",
  CLOSED: "The loan is fully repaid and the application is closed.",
};

/** Verification status pill (mirrors CHECK_PILL in live-pipeline). */
const CHECK_PILL: Record<CheckStatus, string> = {
  PASS: "bg-success-100 text-success-700",
  REVIEW: "bg-warning-100 text-warning-800",
  FAIL: "bg-error-100 text-error-700",
  PENDING: "bg-grey-100 text-muted",
};

function OutcomePill({ state }: { state: JourneyStageState }) {
  const p = OUTCOME_PILL[state];
  const Icon = p.Icon;
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold ${p.className}`}
    >
      <Icon size={12} /> {p.label}
    </span>
  );
}

function humanizeCheck(checkType: string): string {
  return checkType
    .toLowerCase()
    .split(/[_\s]+/)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

// ---------------------------------------------------------------------------
// The popup
// ---------------------------------------------------------------------------

export interface StageDetailDialogProps {
  applicationId: number;
  app: ApplicationView;
  stage: JourneyStage;
  /** The application's FULL event trail (stage.events is only this stage's bucket —
   *  cross-stage facts like the REBORROW origin live in other buckets). */
  allEvents: EventView[];
  open: boolean;
  onClose: () => void;
}

export function StageDetailDialog({
  applicationId,
  app,
  stage,
  allEvents,
  open,
  onClose,
}: StageDetailDialogProps) {
  // Focus moves into the dialog on open and restores to the trigger (the step
  // node) on close/unmount.
  const trapRef = useFocusTrap<HTMLDivElement>(open);

  // Capture-phase Escape: close only this popup and stop the event before the
  // Drawer's (and Dialog's own) document-level Escape listeners fire — so the
  // first Escape closes the popup, a second closes the drawer.
  React.useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopImmediatePropagation();
        e.preventDefault();
        onClose();
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => document.removeEventListener("keydown", onKey, true);
  }, [open, onClose]);

  const idx = STAGE_ORDER.indexOf(stage.key);
  const prevLabel = idx > 0 ? STAGE_LABELS[STAGE_ORDER[idx - 1]] : null;
  const nextLabel = idx < STAGE_ORDER.length - 1 ? STAGE_LABELS[STAGE_ORDER[idx + 1]] : null;
  const reached = stage.state !== "upcoming";
  const titleId = `stage-detail-title-${applicationId}-${stage.key}`;

  // !max-w-2xl: globals.css's un-layered `.modal { max-width: 460px }` outranks
  // plain utilities in the cascade, so the width needs the important modifier.
  return (
    <Dialog open={open} onClose={onClose} className="!max-w-2xl" aria-labelledby={titleId}>
      <div ref={trapRef} tabIndex={-1} className="outline-none">
        <DialogHeader>
          <div className="flex items-center justify-between gap-3">
            <DialogTitle id={titleId}>{stage.label}</DialogTitle>
            <button
              onClick={onClose}
              aria-label="Close step details"
              className="rounded p-1 text-muted hover:bg-grey-100 hover:text-ink"
            >
              <X size={18} />
            </button>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <OutcomePill state={stage.state} />
            <span className="text-xs text-muted">
              Step {idx + 1} of {STAGE_ORDER.length} · Application #{applicationId}
            </span>
          </div>
        </DialogHeader>

        <div className="max-h-[70vh] space-y-6 overflow-y-auto pr-1 text-sm">
          {!reached ? (
            <NotReachedNote stage={stage} />
          ) : (
            <>
              {stage.key === "STARTED" && <StartedSection app={app} allEvents={allEvents} />}
              {stage.key === "KYC" && <KycSection applicationId={applicationId} />}
              {stage.key === "CREDIT_REVIEW" && <CreditSection app={app} />}
              {stage.key === "DISBURSEMENT" && (
                <DisbursementSection app={app} events={stage.events} />
              )}
              {stage.key === "ACTIVE_REPAYMENT" && <ActiveSection app={app} />}
              {stage.key === "CLOSED" && <ClosedSection app={app} />}

              <section>
                <h4 className="mb-2 font-semibold text-ink">Audit trail — {stage.label}</h4>
                {stage.events.length === 0 ? (
                  <p className="text-muted">No events recorded for this stage.</p>
                ) : (
                  <EventTimeline events={stage.events} dense />
                )}
              </section>
            </>
          )}

          <div className="flex items-center justify-between border-t border-line pt-3 text-xs text-muted">
            <span>{prevLabel ? `← ${prevLabel}` : "Start of journey"}</span>
            <span>{nextLabel ? `${nextLabel} →` : "End of journey"}</span>
          </div>
        </div>
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------------------
// Shared little bits
// ---------------------------------------------------------------------------

function Row({ label, value, strong }: { label: string; value: React.ReactNode; strong?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <dt className="text-muted">{label}</dt>
      <dd className={strong ? "text-right font-semibold text-navy" : "text-right text-ink"}>
        {value ?? "—"}
      </dd>
    </div>
  );
}

function SectionError({ error }: { error: unknown }) {
  if (!error) return null;
  // Only an actual authorization error reads as a role limitation; anything else
  // (network, 500…) surfaces its real message in the error convention.
  if (error instanceof ApplicationApiError && error.code === "FORBIDDEN_ROLE") {
    return (
      <p className="text-xs text-muted">Your role doesn&apos;t have access to these details.</p>
    );
  }
  const msg =
    error instanceof Error && error.message ? error.message : "Details couldn't be loaded.";
  return (
    <p className="flex items-start gap-1.5 text-xs text-error-700">
      <AlertTriangle size={12} className="mt-0.5 flex-shrink-0" /> {msg}
    </p>
  );
}

function NotReachedNote({ stage }: { stage: JourneyStage }) {
  return (
    <section className="rounded border border-line bg-grey-50 p-4">
      <p className="font-semibold text-ink">This step hasn&apos;t been reached yet.</p>
      <p className="mt-1 text-muted">{STAGE_BLURB[stage.key]}</p>
    </section>
  );
}

// ---------------------------------------------------------------------------
// STARTED
// ---------------------------------------------------------------------------

function StartedSection({ app, allEvents }: { app: ApplicationView; allEvents: EventView[] }) {
  // The REBORROW event (DRAFT → PRE_APPROVED/REVIEW_PENDING) buckets to the KYC
  // stage, so the source must be detected across the FULL trail, not this bucket.
  const isReborrow = allEvents.some((e) => e.action === "REBORROW");
  return (
    <section>
      <h4 className="mb-2 font-semibold text-ink">Application</h4>
      <dl className="grid grid-cols-1 gap-1 sm:grid-cols-2 sm:gap-x-6">
        <Row
          label="Source"
          value={
            <span className="inline-flex items-center gap-1">
              {isReborrow && <Zap size={12} className="text-gold-dark" />}
              {isReborrow ? "Reborrow (returning)" : "New application"}
            </span>
          }
        />
        <Row label="Purpose" value={app.purpose} />
        <Row label="Requested amount" value={paiseToINR(app.amountRequestedPaise)} />
        <Row label="Eligible limit" value={paiseToINR(app.eligibleLimitPaise)} />
        <Row
          label="Salary day"
          value={app.salaryCreditDay != null ? `Day ${app.salaryCreditDay}` : null}
        />
      </dl>
    </section>
  );
}

// ---------------------------------------------------------------------------
// KYC
// ---------------------------------------------------------------------------

function KycSection({ applicationId }: { applicationId: number }) {
  const progressQ = useQuery({
    queryKey: ["staff-verification-progress", applicationId],
    queryFn: () => staffApi.verificationProgress(applicationId),
    retry: false,
  });
  const checksQ = useQuery({
    queryKey: ["staff-verifications", applicationId],
    queryFn: () => staffApi.verifications(applicationId),
    retry: false,
  });
  const docsQ = useQuery({
    queryKey: ["staff-docs", applicationId],
    queryFn: () => staffApi.documents(applicationId),
    retry: false,
  });
  const briefQ = useQuery({
    queryKey: ["credit-brief", applicationId],
    queryFn: () => staffApi.creditBrief(applicationId),
    retry: false,
  });

  const p = progressQ.data;
  const checks: StepResult[] = checksQ.data ?? [];
  const docs: DocumentView[] = docsQ.data ?? [];
  const brief = briefQ.data;

  return (
    <>
      <section>
        <h4 className="mb-2 flex items-center gap-2 font-semibold text-ink">
          Verification progress
          {(progressQ.isLoading || checksQ.isLoading) && (
            <Loader2 size={12} className="animate-spin text-muted" />
          )}
        </h4>
        {p ? (
          <>
            <div className="mb-2 flex items-center justify-between text-xs text-muted">
              <span>
                <span className="font-semibold text-navy">
                  {p.completed}/{p.required}
                </span>{" "}
                done · {p.percent}%
              </span>
              <span>
                {p.failed > 0 && <span className="text-error-700">{p.failed} failed </span>}
                {p.pending > 0 && <span className="text-warning-800">{p.pending} pending</span>}
              </span>
            </div>
            <div className="h-1.5 w-full overflow-hidden rounded-full bg-grey-200">
              <div
                className="h-full rounded-full bg-success-600 transition-all"
                style={{ width: `${p.percent}%` }}
              />
            </div>
          </>
        ) : (
          <SectionError error={progressQ.error} />
        )}
      </section>

      <section>
        <h4 className="mb-2 font-semibold text-ink">Checks</h4>
        {checks.length === 0 ? (
          checksQ.error ? (
            <SectionError error={checksQ.error} />
          ) : (
            <p className="text-muted">No verification checks recorded yet.</p>
          )
        ) : (
          <ul className="space-y-1.5">
            {checks.map((c, i) => (
              <li
                key={`${c.checkType}-${i}`}
                className="flex flex-wrap items-center justify-between gap-2 rounded border border-line bg-grey-50 px-3 py-2"
              >
                <span className="font-medium text-ink">{humanizeCheck(c.checkType)}</span>
                <span className="flex items-center gap-2">
                  {c.message && <span className="text-xs text-muted">{c.message}</span>}
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs font-semibold ${CHECK_PILL[c.status]}`}
                  >
                    {c.status}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <h4 className="mb-2 flex items-center gap-2 font-semibold text-ink">
          Documents
          {docsQ.isLoading && <Loader2 size={12} className="animate-spin text-muted" />}
        </h4>
        {docs.length === 0 ? (
          docsQ.error ? (
            <SectionError error={docsQ.error} />
          ) : (
            <p className="text-muted">No documents uploaded.</p>
          )
        ) : (
          <ul className="space-y-1.5">
            {docs.map((d) => (
              <DocRow key={d.id} appId={applicationId} doc={d} />
            ))}
          </ul>
        )}
      </section>

      {brief?.available && (
        <section>
          <h4 className="mb-2 font-semibold text-ink">Credit brief</h4>
          <p className="text-ink">
            {brief.starRating != null && (
              <span className="font-semibold text-navy">{brief.starRating.toFixed(1)}★ </span>
            )}
            {brief.recommendation ?? ""}
            {brief.creditScore != null && (
              <span className="text-muted"> · score {brief.creditScore}</span>
            )}
          </p>
          {brief.summary && <p className="mt-1 text-muted">{brief.summary}</p>}
        </section>
      )}
    </>
  );
}

/** View / download a document (presigned for S3, base64 for legacy) — mirrors live-pipeline's DocRow. */
function DocRow({ appId, doc }: { appId: number; doc: DocumentView }) {
  const [busy, setBusy] = React.useState<null | "view" | "download">(null);
  const [err, setErr] = React.useState<string | null>(null);

  const fetchAnd = async (mode: "view" | "download") => {
    setBusy(mode);
    setErr(null);
    try {
      if (doc.s3) {
        const { url } = await staffApi.documentUrl(appId, doc.id);
        window.open(url, "_blank", "noopener,noreferrer");
      } else {
        const content = await staffApi.document(appId, doc.id);
        openDocument(content, mode === "download");
      }
    } catch {
      setErr("Could not open document.");
    } finally {
      setBusy(null);
    }
  };

  return (
    <li className="flex flex-wrap items-center gap-2 rounded border border-line bg-white px-3 py-2">
      <FileText size={15} className="flex-shrink-0 text-navy" />
      <span className="min-w-0 flex-1 truncate text-ink">{doc.fileName}</span>
      <span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">
        {doc.docType}
      </span>
      <button
        onClick={() => fetchAnd("view")}
        disabled={busy != null}
        className="btn btn-sm btn-outline disabled:opacity-50"
      >
        {busy === "view" ? <Loader2 size={13} className="animate-spin" /> : <ExternalLink size={13} />} View
      </button>
      <button
        onClick={() => fetchAnd("download")}
        disabled={busy != null}
        className="btn btn-sm btn-outline disabled:opacity-50"
      >
        {busy === "download" ? <Loader2 size={13} className="animate-spin" /> : <Download size={13} />} Download
      </button>
      {err && <span className="w-full text-xs text-error-700">{err}</span>}
    </li>
  );
}

// ---------------------------------------------------------------------------
// CREDIT_REVIEW — projected cost breakdown (real figures come post-disbursement)
// ---------------------------------------------------------------------------

function CreditSection({ app }: { app: ApplicationView }) {
  const hasAmount = app.amountRequestedPaise != null;
  return (
    <section>
      <h4 className="mb-2 font-semibold text-ink">
        {hasAmount ? "Projected cost (estimate)" : "Projected cost"}
      </h4>
      <ProjectedCostBreakdown app={app} />
    </section>
  );
}

// ---------------------------------------------------------------------------
// DISBURSEMENT — transaction ref + real cost figures once the loan is minted
// ---------------------------------------------------------------------------

/** Pull the disbursal transaction reference out of the audit notes ("Txn/ref: …"). */
function txnRefFromEvents(events: EventView[]): string | null {
  for (const e of events) {
    const m = e.notes?.match(/Txn\/ref:\s*(.+)/i);
    if (m) return m[1].trim();
  }
  return null;
}

function DisbursementSection({ app, events }: { app: ApplicationView; events: EventView[] }) {
  const loanQ = useQuery({
    queryKey: ["staff-loan", app.loanId],
    queryFn: () => staffApi.loan(app.loanId as number),
    enabled: app.loanId != null,
    retry: false,
  });
  const loan = loanQ.data;
  // The authoritative ref is loan.disbursal_txn_ref (captured at release); audit
  // notes are free text and only a fallback.
  const txnRef = loan?.disbursalTxnRef ?? txnRefFromEvents(events);

  return (
    <section>
      <h4 className="mb-2 flex items-center gap-2 font-semibold text-ink">
        Disbursement
        {loanQ.isLoading && <Loader2 size={12} className="animate-spin text-muted" />}
      </h4>
      <dl className="mb-3 grid grid-cols-1 gap-1">
        <Row label="Transaction reference" value={txnRef} />
      </dl>
      {loan ? (
        <LoanBreakdown loan={loan} />
      ) : app.loanId == null ? (
        <p className="text-muted">Funds not released yet — no loan has been minted.</p>
      ) : (
        <SectionError error={loanQ.error} />
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------
// ACTIVE_REPAYMENT — due date, penalty-aware itemization, repayment ledger
// ---------------------------------------------------------------------------

function ActiveSection({ app }: { app: ApplicationView }) {
  const loanQ = useQuery({
    queryKey: ["staff-loan", app.loanId],
    queryFn: () => staffApi.loan(app.loanId as number),
    enabled: app.loanId != null,
    retry: false,
  });
  const outQ = useQuery({
    queryKey: ["staff-loan-out", app.loanId, todayISO()],
    queryFn: () => staffApi.outstanding(app.loanId as number, todayISO()),
    enabled: app.loanId != null,
    retry: false,
  });
  const payQ = useQuery({
    queryKey: ["staff-loan-pay", app.loanId],
    queryFn: () => staffApi.repayments(app.loanId as number),
    enabled: app.loanId != null,
    retry: false,
  });

  const loan = loanQ.data;
  const payments: PaymentView[] = payQ.data ?? [];

  if (app.loanId == null) {
    return (
      <section>
        <h4 className="mb-2 font-semibold text-ink">Repayment</h4>
        <p className="text-muted">The loan isn&apos;t active yet.</p>
      </section>
    );
  }

  return (
    <>
      <section>
        <h4 className="mb-2 flex items-center gap-2 font-semibold text-ink">
          Repayment
          {(loanQ.isLoading || outQ.isLoading) && (
            <Loader2 size={12} className="animate-spin text-muted" />
          )}
        </h4>
        {loan ? (
          <>
            <p className="mb-2 text-muted">
              Due {loan.dueDate ? formatDate(loan.dueDate) : "—"}
            </p>
            <LoanBreakdown loan={loan} outstanding={outQ.data} />
          </>
        ) : (
          <SectionError error={loanQ.error} />
        )}
      </section>

      <section>
        <h4 className="mb-2 flex items-center gap-2 font-semibold text-ink">
          Repayments
          {payQ.isLoading && <Loader2 size={12} className="animate-spin text-muted" />}
        </h4>
        {payments.length === 0 ? (
          <p className="text-muted">No repayments recorded yet.</p>
        ) : (
          <ul className="divide-y divide-line rounded border border-line">
            {payments.map((p) => (
              <PaymentLi key={p.id} p={p} />
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

function PaymentLi({ p }: { p: PaymentView }) {
  const tone =
    p.status === "VERIFIED"
      ? "bg-success-50 text-success-700"
      : p.status === "REJECTED"
        ? "bg-error-50 text-error-700"
        : "bg-gold-50 text-gold-dark";
  return (
    <li className="flex items-center justify-between gap-2 px-3 py-2">
      <span>
        <span className="font-semibold text-ink">{paiseToINR(p.amountPaise)}</span>{" "}
        <span className="text-xs text-muted">
          {p.method === "BANK_TRANSFER" ? "Bank" : p.method}
          {p.txnRef ? ` · ${p.txnRef}` : ""}
          {p.paidOn ? ` · ${formatDate(p.paidOn)}` : ""}
          {p.partial ? " · partial" : ""}
        </span>
      </span>
      <span className={`rounded-full px-2 py-0.5 text-xs font-semibold capitalize ${tone}`}>
        {p.status.replace(/_/g, " ").toLowerCase()}
      </span>
    </li>
  );
}

// ---------------------------------------------------------------------------
// CLOSED — final totals
// ---------------------------------------------------------------------------

function ClosedSection({ app }: { app: ApplicationView }) {
  const loanQ = useQuery({
    queryKey: ["staff-loan", app.loanId],
    queryFn: () => staffApi.loan(app.loanId as number),
    enabled: app.loanId != null,
    retry: false,
  });
  const loan = loanQ.data;
  return (
    <section>
      <h4 className="mb-2 flex items-center gap-2 font-semibold text-ink">
        Final totals
        {loanQ.isLoading && <Loader2 size={12} className="animate-spin text-muted" />}
      </h4>
      {loan ? (
        <>
          <p className="mb-2 text-muted">
            Disbursed {loan.disbursedOn ? formatDate(loan.disbursedOn) : "—"} · closed{" "}
            {loan.status.toLowerCase()}
          </p>
          <LoanBreakdown loan={loan} />
        </>
      ) : app.loanId == null ? (
        <p className="text-muted">No loan is associated with this application.</p>
      ) : (
        <SectionError error={loanQ.error} />
      )}
    </section>
  );
}
