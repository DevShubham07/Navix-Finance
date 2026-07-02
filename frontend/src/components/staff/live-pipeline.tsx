"use client";

/**
 * Shared live-pipeline building blocks for the staff back office.
 *
 * These talk to the REAL backend application state machine via `staffApi`
 * (BFF → Spring), identity coming from the httpOnly `navix_staff` cookie. The
 * full live console (`/staff/applications`) and every per-role pipeline page
 * (kyc-approvals, credit/queue, disbursement, accounting…) compose these so the
 * maker-checker logic lives in exactly one place.
 */

import * as React from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Check, X, Loader2, RefreshCw, FileText, Download, ExternalLink, User, Banknote, ArrowRight, Bell } from "lucide-react";
import { Input, Select, InfoTooltip } from "@/components/ui";
import { CreditBadge } from "@/components/staff/credit-badge";
import { CreditProfileCard } from "@/components/staff/credit-profile-card";
import { hasPermission, type StaffRole, type Permission } from "@/lib/auth/rbac";
import { formatApiError } from "@/lib/api/errors";
import {
  staffApi,
  adminApi,
  customersApi,
  paiseToINR,
  statusLabel,
  openDocument,
  type ApplicationStatus,
  type ApplicationView,
  type EventView,
  type DocumentView,
  type LoanView,
  type StepResult,
  type CheckStatus,
} from "@/lib/api/applications";
import { formatDate, formatDateTime } from "@/lib/utils";
import { LoanDetailDialog } from "@/components/staff/loan-detail-dialog";
import { CustomerDetailDialog } from "@/components/staff/customer-detail-dialog";

/** Loan statuses that mean the loan is still live (vs. a past/closed loan). */
const OPEN_LOAN_STATUSES = new Set(["ACTIVE", "OVERDUE", "IN_COLLECTIONS", "DISBURSED", "DEFAULTED"]);

// ---------------------------------------------------------------------------
// Live staff session (navix_staff cookie)
// ---------------------------------------------------------------------------

export interface StaffMe {
  id: string;
  name: string;
  role: StaffRole;
}

export async function fetchStaffMe(): Promise<StaffMe | null> {
  const res = await fetch("/api/auth/staff/me", { cache: "no-store", credentials: "same-origin" });
  if (!res.ok) return null;
  const json = (await res.json()) as { session: StaffMe | null };
  return json.session;
}

/** React Query wrapper for the live staff session. */
export function useStaffMe() {
  return useQuery({ queryKey: ["staff-me"], queryFn: fetchStaffMe });
}

export function errMessage(e: unknown): string {
  return formatApiError(e, "Action failed.");
}

export const ROLE_LABEL: Record<StaffRole, string> = {
  KYC_APPROVER: "KYC Approver",
  CREDIT_EXECUTIVE: "Credit Executive",
  CREDIT_HEAD: "Credit Head",
  DISBURSEMENT_HEAD: "Disbursement Head",
  ACCOUNTANT: "Accountant",
  COLLECTION_HEAD: "Collection Head",
  COLLECTION_EXECUTIVE: "Collection Executive",
  ADMIN: "Administrator",
  DEVELOPER: "Developer",
};

/** Roles that drive the application state machine. */
export const PIPELINE_ROLES: StaffRole[] = [
  "KYC_APPROVER",
  "CREDIT_HEAD",
  "CREDIT_EXECUTIVE",
  "DISBURSEMENT_HEAD",
  "ACCOUNTANT",
];

// ---------------------------------------------------------------------------
// Generic status-backed queue panel
// ---------------------------------------------------------------------------

export function StatusQueue({
  title,
  status,
  actions,
  info,
  filter,
}: {
  title: string;
  status: ApplicationStatus;
  actions: (app: ApplicationView) => React.ReactNode;
  /** Optional ⓘ explanation shown beside the queue title. */
  info?: string;
  /** Optional client-side filter to split one status into sections (e.g. fast-track disbursement). */
  filter?: (app: ApplicationView) => boolean;
}) {
  const q = useQuery({
    queryKey: ["staff-queue", status],
    queryFn: () => staffApi.listByStatus(status),
    refetchInterval: 8000,
  });

  const apps = filter ? (q.data ?? []).filter(filter) : q.data ?? [];

  return (
    <QueuePanel
      title={title}
      countBadge={status}
      apps={apps}
      isLoading={q.isLoading}
      error={q.error}
      onRefresh={() => q.refetch()}
      actions={actions}
      info={info}
    />
  );
}

export function CreditQueuePanel() {
  const q = useQuery({
    queryKey: ["staff-queue", "credit-queue"],
    queryFn: () => staffApi.creditQueue(),
    refetchInterval: 8000,
  });

  return (
    <QueuePanel
      title="Credit queue — assign an executive"
      countBadge="credit-queue"
      apps={q.data ?? []}
      isLoading={q.isLoading}
      error={q.error}
      onRefresh={() => q.refetch()}
      actions={(app) => <AssignActions app={app} />}
      info="KYC-approved applications the borrower has applied on. Assign each to an ACTIVE Credit Executive to start the credit review."
    />
  );
}

function QueuePanel({
  title,
  countBadge,
  apps,
  isLoading,
  error,
  onRefresh,
  actions,
  info,
}: {
  title: string;
  countBadge: string;
  apps: ApplicationView[];
  isLoading: boolean;
  error: unknown;
  onRefresh: () => void;
  actions: (app: ApplicationView) => React.ReactNode;
  info?: string;
}) {
  return (
    <section className="rounded border border-line bg-white shadow-sm">
      <header className="flex items-center justify-between gap-3 border-b border-line px-5 py-3">
        <div className="flex items-center gap-2">
          <h2 className="font-serif text-lg font-semibold text-navy">{title}</h2>
          {info && <InfoTooltip content={info} />}
          {isLoading && <Loader2 size={15} className="animate-spin text-muted" />}
          <span className="rounded-full bg-navy-tint px-2.5 py-0.5 text-xs font-semibold text-navy">{apps.length}</span>
        </div>
        <button
          onClick={onRefresh}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          <RefreshCw size={13} /> Refresh
        </button>
      </header>

      {error ? (
        <p className="px-5 py-4 text-sm text-error-700">{errMessage(error)}</p>
      ) : apps.length === 0 ? (
        <p className="px-5 py-6 text-center text-sm text-muted">
          Nothing in the <code className="text-xs">{countBadge}</code> queue.
        </p>
      ) : (
        <ul className="divide-y divide-line">
          {apps.map((app) => (
            <AppRow key={app.id} app={app} actions={actions} />
          ))}
        </ul>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------
// One application row + expandable events trail
// ---------------------------------------------------------------------------

function AppRow({
  app,
  actions,
}: {
  app: ApplicationView;
  actions: (app: ApplicationView) => React.ReactNode;
}) {
  const [openDetail, setOpenDetail] = React.useState(false);
  return (
    <li className="px-5 py-3">
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
        <div className="flex items-center gap-2">{actions(app)}</div>
      </div>

      <div className="mt-2 flex flex-wrap gap-2">
        {/* Full customer detail (basics, past loans/payments, documents, all logs, remarks) in a popup —
            no page navigation, no inline "extend screen". */}
        <button onClick={() => setOpenDetail(true)} className="btn btn-sm btn-navy">
          <User size={14} /> Open details
        </button>
        {/* KYC-approver verification manual-override tooling (application-specific). */}
        <CustomerReview applicationId={app.id} />
      </div>

      <CustomerDetailDialog customerId={openDetail ? app.customerId : null} onClose={() => setOpenDetail(false)} />
    </li>
  );
}

export function EventsTrail({ id }: { id: number }) {
  const q = useQuery({
    queryKey: ["staff-events", id],
    queryFn: () => staffApi.events(id),
  });

  if (q.isLoading) {
    return <p className="mt-2 flex items-center gap-1.5 text-xs text-muted"><Loader2 size={13} className="animate-spin" /> Loading events…</p>;
  }
  if (q.error) return <p className="mt-2 text-xs text-error-700">{errMessage(q.error)}</p>;
  const events: EventView[] = q.data ?? [];
  if (events.length === 0) return <p className="mt-2 text-xs text-muted">No events yet.</p>;

  return (
    <ul className="mt-2 space-y-1.5 rounded bg-grey-50 p-3 text-xs">
      {events.map((e) => (
        <li key={e.id} className="flex flex-wrap items-center justify-between gap-2">
          <span className="text-ink">
            <span className="font-semibold">{e.action ?? "transition"}</span>{" "}
            <span className="text-muted">
              {e.fromStatus ? `${e.fromStatus} → ` : ""}{e.toStatus ?? ""}
            </span>
            {e.actorRole ? <span className="text-muted"> · {e.actorRole}</span> : null}
            {e.notes ? <span className="text-muted"> · {e.notes}</span> : null}
          </span>
          <span className="flex-shrink-0 text-muted">{e.at ? formatDateTime(e.at) : ""}</span>
        </li>
      ))}
    </ul>
  );
}

// ---------------------------------------------------------------------------
// Customer review: KYC details + documents (any reviewing role)
// ---------------------------------------------------------------------------

/**
 * Permissions that legitimately need to read customer PII (name, masked PAN/Aadhaar, salary,
 * employer, address, documents). Collection roles (only `collections:*`) and DEVELOPER (no perms)
 * are intentionally excluded — they have no need-to-know for a borrower's salary/employer.
 */
const REVIEW_PERMS: Permission[] = [
  "kyc:approve",
  "loan:review",
  "loan:approve",
  "loan:disburse",
  "loan:activate",
];

export function CustomerReview({ applicationId }: { applicationId: number }) {
  const role = useStaffMe().data?.role;
  const canReview = role != null && REVIEW_PERMS.some((p) => hasPermission(role, p));
  const [load, setLoad] = React.useState(false);
  const profileQ = useQuery({
    queryKey: ["staff-profile", applicationId],
    queryFn: () => staffApi.getProfile(applicationId),
    enabled: load && canReview,
    retry: false,
  });
  const docsQ = useQuery({
    queryKey: ["staff-docs", applicationId],
    queryFn: () => staffApi.documents(applicationId),
    enabled: load && canReview,
    retry: false,
  });

  if (role == null) return null;
  if (!canReview) {
    return <NoAccessNotice message="Customer details (incl. PII) aren't available to your role." />;
  }

  if (!load) {
    return (
      <button onClick={() => setLoad(true)} className="btn btn-sm btn-outline">
        <User size={14} /> Show customer details &amp; documents
      </button>
    );
  }

  const p = profileQ.data;

  return (
    <div className="w-full space-y-3">
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 font-serif text-base font-semibold text-navy">
        <User size={16} /> Customer details
        {(profileQ.isFetching || docsQ.isFetching) && <Loader2 size={14} className="animate-spin text-muted" />}
        <button onClick={() => setLoad(false)} className="ml-auto text-xs font-normal text-muted hover:text-ink">
          Hide
        </button>
      </div>

      {profileQ.isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : !p ? (
        <p className="text-sm text-muted">No KYC details were submitted for this application.</p>
      ) : (
        <dl className="divide-y divide-line text-sm">
          <Row label="Full name" value={p.fullName} />
          <Row label="PAN" value={p.pan} mono />
          <Row
            label="Aadhaar"
            value={
              p.aadhaarVerified ? (
                <span className="inline-flex items-center justify-end gap-1.5"><VerifiedPill /></span>
              ) : (
                <span className="text-muted">Not verified</span>
              )
            }
          />
          <Row label="Mobile" value={p.mobile} mono />
          <Row label="Email" value={p.email} />
          <Row label="Date of birth" value={p.dob} />
          <Row label="Address" value={p.address} />
          <Row label="Employer" value={p.employer} />
          <Row label="Employment" value={p.employmentStatus} />
          <Row label="Monthly salary" value={p.monthlySalaryPaise != null ? paiseToINR(p.monthlySalaryPaise) : null} />
          <Row label="Salary bank" value={p.salaryBank} />
          <Row label="CIBIL score" value={p.creditScore != null ? String(p.creditScore) : null} mono />
          <Row label="Risk category" value={p.riskCategory} />
          <Row label="Bureau" value={p.bureauSource} />
          <Row label="Identity match" value={p.nameMatchScore != null ? `${Math.round(p.nameMatchScore * 100)}%` : null} />
        </dl>
      )}

      <VerificationCards applicationId={applicationId} />

      <div className="mb-2 mt-5 text-xs font-semibold uppercase tracking-wide text-muted">Documents</div>
      {docsQ.isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : docsQ.error ? (
        <p className="text-sm text-error-700">{errMessage(docsQ.error)}</p>
      ) : (docsQ.data ?? []).length === 0 ? (
        <p className="text-sm text-muted">No documents uploaded.</p>
      ) : (
        <ul className="space-y-1.5">
          {docsQ.data!.map((d) => (
            <DocRow key={d.id} appId={applicationId} doc={d} />
          ))}
        </ul>
      )}
    </div>
    <CreditProfileCard applicationId={applicationId} />
    </div>
  );
}

function Row({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4 py-2">
      <dt className="text-muted">{label}</dt>
      <dd className={mono ? "text-right font-mono text-ink" : "text-right text-ink"}>{value || "—"}</dd>
    </div>
  );
}

/** Small green "Verified" pill shown next to an identity field that's been verified. */
function VerifiedPill() {
  return (
    <span className="inline-flex items-center gap-0.5 rounded-full bg-success-50 px-1.5 py-0.5 text-[10px] font-semibold text-success-700">
      <Check size={10} /> Verified
    </span>
  );
}

/** Status pill styling per verification outcome (green PASS / amber REVIEW / red FAIL / grey PENDING). */
const CHECK_PILL: Record<CheckStatus, string> = {
  PASS: "bg-success-100 text-success-700",
  REVIEW: "bg-warning-100 text-warning-800",
  FAIL: "bg-error-100 text-error-700",
  PENDING: "bg-grey-100 text-muted",
};

/** "PENNY_DROP" -> "Penny drop". */
function humanizeCheck(checkType: string): string {
  return checkType
    .toLowerCase()
    .split(/[_\s]+/)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

/** "monthlySalaryPaise" -> "Monthly salary paise". */
function humanizeKey(key: string): string {
  return key
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_\s]+/g, " ")
    .replace(/^./, (c) => c.toUpperCase());
}

function stringifyDerived(value: unknown): string {
  if (value == null) return "—";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

/**
 * Verification cards for an application's automated checks (PAN, email, address, salary, …).
 * One card per {@link StepResult}: the check name, a status pill, the message, and the key
 * `derived` fields the reviewer needs. Loaded on demand inside {@link CustomerReview}.
 */
function VerificationCards({ applicationId }: { applicationId: number }) {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["staff-verifications", applicationId],
    queryFn: () => staffApi.verifications(applicationId),
    retry: false,
  });
  // Required-step completion snapshot (Phase 3.2 progress tracker).
  const progressQ = useQuery({
    queryKey: ["staff-verification-progress", applicationId],
    queryFn: () => staffApi.verificationProgress(applicationId),
    retry: false,
  });
  // KYC-approver / admin manual override of a stuck or inconclusive step (Phase 3.1).
  const decide = useMutation({
    mutationFn: (vars: { checkType: string; decision: boolean }) =>
      staffApi.manualVerificationDecision(applicationId, vars.checkType, vars.decision),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["staff-verifications", applicationId] });
      qc.invalidateQueries({ queryKey: ["staff-verification-progress", applicationId] });
    },
  });
  // KYC-approver / admin nudge the borrower with their pending steps (Phase 3.4).
  const remind = useMutation({ mutationFn: () => staffApi.sendReminder(applicationId) });

  const steps: StepResult[] = q.data ?? [];
  const p = progressQ.data;

  return (
    <div className="mt-5">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold uppercase tracking-wide text-muted">Verification checks</span>
          <PermissionGate permission="kyc:approve">
            <button
              onClick={() => remind.mutate()}
              disabled={remind.isPending || remind.isSuccess}
              title="Remind the borrower of their pending verification steps"
              className="inline-flex items-center gap-1 rounded border border-line px-2 py-0.5 text-[11px] font-semibold text-navy hover:bg-navy-tint disabled:opacity-50"
            >
              {remind.isPending ? <Loader2 size={11} className="animate-spin" /> : <Bell size={11} />}
              {remind.isSuccess ? (remind.data?.sent ? "Reminded" : "Nothing pending") : "Send reminder"}
            </button>
          </PermissionGate>
        </div>
        {p && (
          <span className="text-xs text-muted">
            <span className="font-semibold text-navy">{p.completed}/{p.required}</span> done · {p.percent}%
            {p.failed > 0 ? <span className="text-error-700"> · {p.failed} failed</span> : null}
            {p.pending > 0 ? <span className="text-warning-800"> · {p.pending} pending</span> : null}
          </span>
        )}
      </div>
      {p && (
        <div className="mb-3 h-1.5 w-full overflow-hidden rounded-full bg-grey-200">
          <div className="h-full rounded-full bg-success-600 transition-all" style={{ width: `${p.percent}%` }} />
        </div>
      )}
      {q.isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : steps.length === 0 ? (
        <p className="text-sm text-muted">No verification checks recorded yet.</p>
      ) : (
        <div className="grid gap-2 sm:grid-cols-2">
          {steps.map((s, i) => {
            const entries = Object.entries(s.derived ?? {});
            return (
              <div key={`${s.checkType}-${i}`} className="rounded border border-line bg-grey-50 p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-sm font-semibold text-navy">{humanizeCheck(s.checkType)}</span>
                  <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${CHECK_PILL[s.status]}`}>
                    {s.status}
                  </span>
                </div>
                {s.message ? <p className="mt-1 text-xs text-ink/90">{s.message}</p> : null}
                {entries.length > 0 && (
                  <dl className="mt-2 space-y-0.5 text-xs">
                    {entries.map(([k, v]) => (
                      <div key={k} className="flex items-start justify-between gap-3">
                        <dt className="text-muted">{humanizeKey(k)}</dt>
                        <dd className="break-all text-right text-ink">{stringifyDerived(v)}</dd>
                      </div>
                    ))}
                  </dl>
                )}
                <PermissionGate permission="kyc:approve">
                  <div className="mt-2 flex items-center gap-1.5 border-t border-line pt-2">
                    <button
                      onClick={() => decide.mutate({ checkType: s.checkType, decision: true })}
                      disabled={decide.isPending}
                      className="rounded border border-success-600 px-2 py-0.5 text-xs font-semibold text-success-700 hover:bg-success-50 disabled:opacity-50"
                    >
                      Approve
                    </button>
                    <button
                      onClick={() => decide.mutate({ checkType: s.checkType, decision: false })}
                      disabled={decide.isPending}
                      className="rounded border border-error-600 px-2 py-0.5 text-xs font-semibold text-error-700 hover:bg-error-50 disabled:opacity-50"
                    >
                      Reject
                    </button>
                    <span className="text-[11px] text-muted">manual override</span>
                  </div>
                </PermissionGate>
              </div>
            );
          })}
        </div>
      )}
      {decide.error && <p className="mt-2 text-xs text-error-700">{errMessage(decide.error)}</p>}
    </div>
  );
}

/** Open any application by ID to load its customer review — gated to reviewer roles (not collections/dev). */
export function ReviewLookup() {
  const [input, setInput] = React.useState("");
  const [openId, setOpenId] = React.useState<number | null>(null);
  return (
    <PermissionGate permission={REVIEW_PERMS} fallback={null}>
    <section className="rounded border border-line bg-white shadow-sm">
      <header className="border-b border-line px-5 py-3">
        <h2 className="font-serif text-lg font-semibold text-navy">Review an application</h2>
        <p className="mt-0.5 text-xs text-muted">
          Open any application by its ID to view the customer&apos;s details and documents — loaded on demand.
        </p>
      </header>
      <div className="flex flex-wrap items-end gap-2 px-5 py-4">
        <Input
          label="Application ID"
          value={input}
          onChange={(e) => setInput(e.target.value.replace(/\D/g, ""))}
          placeholder="e.g. 8"
          className="!mb-0"
          inputClassName="w-32"
        />
        <button
          onClick={() => setOpenId(input ? Number.parseInt(input, 10) : null)}
          disabled={!input}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          Open review
        </button>
      </div>
      {openId != null && (
        <div className="border-t border-line px-5 py-4">
          <div className="mb-2 text-sm font-semibold text-navy">Application #{openId}</div>
          <CustomerReview key={openId} applicationId={openId} />
        </div>
      )}
    </section>
    </PermissionGate>
  );
}

function DocRow({ appId, doc }: { appId: number; doc: DocumentView }) {
  const [busy, setBusy] = React.useState<null | "view" | "download">(null);
  const [err, setErr] = React.useState<string | null>(null);

  const fetchAnd = async (mode: "view" | "download") => {
    setBusy(mode);
    setErr(null);
    try {
      if (doc.s3) {
        // S3-backed: open the short-lived presigned URL in a new tab (browser <-> S3 directly).
        const { url } = await staffApi.documentUrl(appId, doc.id);
        window.open(url, "_blank", "noopener,noreferrer");
      } else {
        // Legacy inline storage: fetch the base64 bytes and view/download via a Blob URL.
        const content = await staffApi.document(appId, doc.id);
        openDocument(content, mode === "download");
      }
    } catch (e) {
      setErr(errMessage(e));
    } finally {
      setBusy(null);
    }
  };

  return (
    <li className="flex flex-wrap items-center gap-2 rounded border border-line bg-white px-3 py-2 text-sm">
      <FileText size={15} className="flex-shrink-0 text-navy" />
      <span className="min-w-0 flex-1 truncate text-ink">{doc.fileName}</span>
      <span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">{doc.docType}</span>
      {doc.sizeBytes != null && <span className="text-xs text-muted">{formatBytes(doc.sizeBytes)}</span>}
      <button onClick={() => fetchAnd("view")} disabled={busy != null} className="btn btn-sm btn-outline disabled:opacity-50">
        {busy === "view" ? <Loader2 size={13} className="animate-spin" /> : <ExternalLink size={13} />} View
      </button>
      <button onClick={() => fetchAnd("download")} disabled={busy != null} className="btn btn-sm btn-outline disabled:opacity-50">
        {busy === "download" ? <Loader2 size={13} className="animate-spin" /> : <Download size={13} />} Download
      </button>
      {err && <span className="w-full text-xs text-error-700">{err}</span>}
    </li>
  );
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * The customer's loan history (current + past), loaded on demand so a queue of N rows doesn't fan
 * out N borrower-history fetches. Open to every staff role (the customers roll-up is). Keyed on the
 * customer id so a staffer inspecting an application sees the borrower's amount/due-date context.
 */
export function LoanHistory({ customerId }: { customerId: number }) {
  const [load, setLoad] = React.useState(false);
  const q = useQuery({
    queryKey: ["customer-loans", customerId],
    queryFn: () => customersApi.get(customerId),
    enabled: load,
    retry: false,
  });
  const [selected, setSelected] = React.useState<{ loan: LoanView; applicationId: number | null } | null>(null);

  if (!load) {
    return (
      <button onClick={() => setLoad(true)} className="btn btn-sm btn-outline">
        <Banknote size={14} /> Show loan history
      </button>
    );
  }

  const c = q.data;
  const current = c?.loans.find((l) => OPEN_LOAN_STATUSES.has(l.status)) ?? null;
  const past = c ? c.loans.filter((l) => l !== current) : [];
  const appIdFor = (loanId: number) => c?.applications.find((a) => a.loanId === loanId)?.id ?? null;
  const open = (loan: LoanView) => setSelected({ loan, applicationId: appIdFor(loan.id) });

  return (
    <div className="w-full rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 font-serif text-base font-semibold text-navy">
        <Banknote size={16} /> Loan history
        {q.isFetching && <Loader2 size={14} className="animate-spin text-muted" />}
        <Link href={`/staff/customers/${customerId}`} className="ml-auto inline-flex items-center gap-1 text-xs font-normal text-navy hover:underline">
          Full profile <ArrowRight size={12} />
        </Link>
      </div>

      {q.isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : !c || c.loans.length === 0 ? (
        <p className="text-sm text-muted">No loans yet for this customer.</p>
      ) : (
        <div className="space-y-3 text-sm">
          {current && (
            <button
              type="button"
              onClick={() => open(current)}
              className="w-full rounded border border-navy/20 bg-navy-tint/40 p-3 text-left transition hover:border-navy hover:bg-navy-tint/70"
            >
              <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-navy">Current loan · view details</div>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-ink">Loan #{current.id} · {paiseToINR(current.principalPaise)}</span>
                <span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">{current.status}</span>
              </div>
              <div className="mt-0.5 text-xs text-muted">
                net {paiseToINR(current.netDisbursedPaise)} · disbursed {current.disbursedOn ? formatDate(current.disbursedOn) : "—"} · due {current.dueDate ? formatDate(current.dueDate) : "—"} · outstanding {paiseToINR(current.outstandingPaise)}
              </div>
            </button>
          )}
          <div>
            <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Past loans ({past.length})</div>
            {past.length === 0 ? (
              <p className="text-xs text-muted">None.</p>
            ) : (
              <ul className="divide-y divide-line">
                {past.map((l: LoanView) => (
                  <li key={l.id}>
                    <button
                      type="button"
                      onClick={() => open(l)}
                      className="w-full rounded py-1.5 text-left transition hover:bg-grey-50"
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <span className="text-ink underline-offset-2 hover:underline">Loan #{l.id} · {paiseToINR(l.principalPaise)}</span>
                        <span className="rounded-full bg-grey-100 px-2 py-0.5 text-xs font-semibold text-muted">{l.status}</span>
                      </div>
                      <div className="mt-0.5 text-xs text-muted">
                        net {paiseToINR(l.netDisbursedPaise)} · disbursed {l.disbursedOn ? formatDate(l.disbursedOn) : "—"} · due {l.dueDate ? formatDate(l.dueDate) : "—"} · outstanding {paiseToINR(l.outstandingPaise)}
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}

      {selected && (
        <LoanDetailDialog
          loan={selected.loan}
          applicationId={selected.applicationId}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Per-stage action clusters (each invalidates the relevant queues on success)
// ---------------------------------------------------------------------------

/** Invalidate every queue + this app's events so the row leaves/updates. */
function useRefreshAfterAction() {
  const qc = useQueryClient();
  return (id: number) => {
    qc.invalidateQueries({ queryKey: ["staff-queue"] });
    qc.invalidateQueries({ queryKey: ["staff-events", id] });
    qc.invalidateQueries({ queryKey: ["staff-dashboard-counts"] });
    qc.invalidateQueries({ queryKey: ["staff-dashboard-queue"] });
  };
}

function ApproveRejectButtons({
  onApprove,
  onReject,
  pending,
  approveLabel = "Approve",
  rejectLabel = "Reject",
}: {
  onApprove: () => void;
  onReject: () => void;
  pending: boolean;
  approveLabel?: string;
  rejectLabel?: string;
}) {
  return (
    <>
      <button
        onClick={onApprove}
        disabled={pending}
        className="btn btn-sm bg-success-600 border-success-600 text-white hover:bg-success-700 disabled:opacity-50"
      >
        {pending ? <Loader2 size={14} className="animate-spin" /> : <Check size={14} />} {approveLabel}
      </button>
      <button
        onClick={onReject}
        disabled={pending}
        className="btn btn-sm bg-error-600 border-error-600 text-white hover:bg-error-700 disabled:opacity-50"
      >
        <X size={14} /> {rejectLabel}
      </button>
    </>
  );
}

function ActionError({ error }: { error: unknown }) {
  if (!error) return null;
  return <span className="text-xs text-error-700">{errMessage(error)}</span>;
}

/**
 * Gate a maker-checker action cluster on the signed-in role's permission.
 *
 * The backend is the source of truth (requireRole + SoD via the event trail),
 * but the UI must not even *offer* a step the role can't take — e.g. a Credit
 * Executive must never see the Credit Head's approve/reject. ADMIN holds every
 * permission, so it retains oversight across all steps.
 */
function ActionGate({ permission, children }: { permission: Permission; children: React.ReactNode }) {
  const me = useStaffMe();
  const role = me.data?.role;
  if (!role) return null; // session still loading / not signed in
  if (!hasPermission(role, permission)) {
    return <span className="text-xs italic text-muted">Not your step</span>;
  }
  return <>{children}</>;
}

/**
 * Gate a whole panel/section on the signed-in role's permission(s).
 *
 * Unlike {@link ActionGate} (which hides only the buttons inside an always-rendered
 * panel), this hides the entire child — used so each role sees only the queues it
 * works on (e.g. a Credit Executive doesn't see the Credit Head's decision panel).
 * Pass an array to allow any-of. ADMIN holds every permission, so it sees all panels.
 */
export function PermissionGate({
  permission,
  children,
  fallback = null,
}: {
  permission: Permission | Permission[];
  children: React.ReactNode;
  fallback?: React.ReactNode;
}) {
  const role = useStaffMe().data?.role;
  if (!role) return null; // session still loading / not signed in
  const perms = Array.isArray(permission) ? permission : [permission];
  if (!perms.some((p) => hasPermission(role, p))) return <>{fallback}</>;
  return <>{children}</>;
}

/** Friendly fallback for a page/section the signed-in role can't access. */
export function NoAccessNotice({ message = "You don't have access to this queue." }: { message?: string }) {
  return (
    <p className="rounded border border-line bg-white px-5 py-8 text-center text-sm text-muted shadow-sm">{message}</p>
  );
}

/**
 * Approve/Reject cluster that also captures a transaction id / reference, stored
 * in the action's audit `notes`. Used by the Disbursement Head (approve the
 * release) and the Accountant (confirm the bank transfer). Approve is disabled
 * until a reference is entered; Reject doesn't require one (the text, if any, is
 * passed as the rejection/failure reason).
 */
function ProofDecisionActions({
  permission,
  approveLabel,
  rejectLabel,
  pending,
  error,
  onApprove,
  onReject,
  requireProofOnApprove = true,
  proofPlaceholder = "Transaction id / reference",
  hint,
}: {
  permission: Permission;
  approveLabel: string;
  rejectLabel: string;
  pending: boolean;
  error: unknown;
  onApprove: (proof: string) => void;
  onReject: (proof?: string) => void;
  requireProofOnApprove?: boolean;
  proofPlaceholder?: string;
  hint?: string;
}) {
  const [proof, setProof] = React.useState("");
  const proofMissing = requireProofOnApprove && proof.trim().length === 0;
  return (
    <ActionGate permission={permission}>
      <div className="flex flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <Input
            aria-label="Transaction id or reference"
            value={proof}
            onChange={(e) => setProof(e.target.value)}
            inputClassName="w-56"
            className="!mb-0"
            placeholder={proofPlaceholder}
          />
          <button
            onClick={() => onApprove(proof.trim())}
            disabled={pending || proofMissing}
            className="btn btn-sm bg-success-600 border-success-600 text-white hover:bg-success-700 disabled:opacity-50"
          >
            {pending ? <Loader2 size={14} className="animate-spin" /> : <Check size={14} />} {approveLabel}
          </button>
          <button
            onClick={() => onReject(proof.trim() || undefined)}
            disabled={pending}
            className="btn btn-sm bg-error-600 border-error-600 text-white hover:bg-error-700 disabled:opacity-50"
          >
            <X size={14} /> {rejectLabel}
          </button>
          <ActionError error={error} />
        </div>
        {hint && <p className="text-xs text-muted">{hint}</p>}
      </div>
    </ActionGate>
  );
}

export function KycActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) => staffApi.kycDecision(app.id, decision),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ActionGate permission="kyc:approve">
      <div className="flex items-center gap-2">
        <ApproveRejectButtons pending={m.isPending} onApprove={() => m.mutate(true)} onReject={() => m.mutate(false)} />
        <ActionError error={m.error} />
      </div>
    </ActionGate>
  );
}

/**
 * Reborrow review (KYC approver): clear or reject a returning borrower flagged for past delinquency.
 * Separate from fresh KYC — REVIEW_PENDING → PRE_APPROVED (clear) / REJECTED. Same `kyc:approve` perm.
 */
export function ReviewActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) => staffApi.reviewDecision(app.id, decision),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ActionGate permission="kyc:approve">
      <div className="flex items-center gap-2">
        <ApproveRejectButtons
          pending={m.isPending}
          onApprove={() => m.mutate(true)}
          onReject={() => m.mutate(false)}
          approveLabel="Clear borrower"
          rejectLabel="Reject"
        />
        <ActionError error={m.error} />
      </div>
    </ActionGate>
  );
}

export function AssignActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const me = useStaffMe();
  const isAdmin = me.data?.role === "ADMIN";
  const [execId, setExecId] = React.useState("");
  // Assignee picker: only ACTIVE Credit Executives (dfd §13.4 activation gating).
  const execQ = useQuery({
    queryKey: ["staff-executives"],
    queryFn: () => adminApi.listStaff(),
    staleTime: 60_000,
  });
  const execs = (execQ.data ?? []).filter(
    (s) => s.role === "CREDIT_EXECUTIVE" && s.status === "ACTIVE",
  );
  const m = useMutation({
    mutationFn: () => staffApi.assign(app.id, Number.parseInt(execId, 10)),
    onSuccess: () => refresh(app.id),
  });
  // ADMIN oversight: self-assign and drive the credit step solo (the backend lifts the
  // active-Credit-Executive requirement for ADMIN).
  const mSelf = useMutation({
    mutationFn: () => staffApi.assign(app.id, Number(me.data!.id)),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ActionGate permission="loan:approve">
      <div className="flex items-center gap-2">
        {execQ.isLoading ? (
          <span className="text-xs text-muted">Loading executives…</span>
        ) : execs.length === 0 ? (
          <span className="text-xs text-muted">No active credit executives</span>
        ) : (
          <>
            <Select
              aria-label="Credit executive"
              className="!mb-0"
              value={execId}
              onChange={(e) => setExecId(e.target.value)}
            >
              <option value="" disabled>
                Select executive…
              </option>
              {execs.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </Select>
            <button
              onClick={() => m.mutate()}
              disabled={m.isPending || !execId}
              className="btn btn-sm btn-navy disabled:opacity-50"
            >
              {m.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Assign
            </button>
          </>
        )}
        {isAdmin && me.data && (
          <button
            onClick={() => mSelf.mutate()}
            disabled={mSelf.isPending}
            className="btn btn-sm btn-outline disabled:opacity-50"
            title="Assign this credit review to yourself (admin oversight)"
          >
            {mSelf.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Assign to me
          </button>
        )}
        <ActionError error={m.error || mSelf.error} />
      </div>
    </ActionGate>
  );
}

export function ExecActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) => staffApi.execDecision(app.id, decision),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ActionGate permission="loan:review">
      <div className="flex items-center gap-2">
        <ApproveRejectButtons pending={m.isPending} onApprove={() => m.mutate(true)} onReject={() => m.mutate(false)} />
        <ActionError error={m.error} />
      </div>
    </ActionGate>
  );
}

export function HeadActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) =>
      staffApi.headDecision(app.id, {
        decision,
        // Approve at the requested amount by default.
        approvedAmountPaise: decision ? app.amountRequestedPaise ?? undefined : undefined,
      }),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ActionGate permission="loan:approve">
      <div className="flex items-center gap-2">
        <ApproveRejectButtons pending={m.isPending} onApprove={() => m.mutate(true)} onReject={() => m.mutate(false)} />
        <ActionError error={m.error} />
      </div>
    </ActionGate>
  );
}

/**
 * KYC-approver credit fast-path: on an applied KYC_APPROVED application the KYC approver clears the
 * credit gate in one step (→ DISBURSEMENT_PENDING) or rejects it. The action only appears once the
 * borrower has chosen an amount (amountRequestedPaise set).
 */
export function KycCreditActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) =>
      staffApi.kycCreditDecision(app.id, {
        decision,
        approvedAmountPaise: decision ? app.amountRequestedPaise ?? undefined : undefined,
      }),
    onSuccess: () => refresh(app.id),
  });
  if (app.amountRequestedPaise == null) {
    return <span className="text-xs italic text-muted">Awaiting the borrower&apos;s amount</span>;
  }
  return (
    <ActionGate permission="loan:approve">
      <div className="flex items-center gap-2">
        <ApproveRejectButtons
          pending={m.isPending}
          onApprove={() => m.mutate(true)}
          onReject={() => m.mutate(false)}
          approveLabel="Approve for disbursement"
          rejectLabel="Reject"
        />
        <ActionError error={m.error} />
      </div>
    </ActionGate>
  );
}

export function DisbursementActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (vars: { decision: boolean; txnRef?: string; notes?: string }) =>
      staffApi.disbursementDecision(app.id, vars.decision, vars.txnRef, vars.notes),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ProofDecisionActions
      permission="loan:disburse"
      approveLabel="Approve & release"
      rejectLabel="Reject"
      pending={m.isPending}
      error={m.error}
      // Txn id optional here: with one the loan activates immediately (skips the
      // accountant); without one it routes to the accountant to confirm.
      requireProofOnApprove={false}
      proofPlaceholder="Transaction id (optional)"
      hint="Enter a transaction id to release & activate immediately, or approve without one to send it to the accountant."
      onApprove={(proof) => m.mutate({ decision: true, txnRef: proof || undefined, notes: proof ? `Txn/ref: ${proof}` : undefined })}
      onReject={(proof) => m.mutate({ decision: false, notes: proof })}
    />
  );
}

export function AccountantActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (vars: { decision: boolean; txnRef?: string; notes?: string }) =>
      staffApi.accountantValidate(app.id, vars.decision, vars.txnRef, vars.notes),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ProofDecisionActions
      permission="loan:activate"
      approveLabel="Confirm transfer"
      rejectLabel="Mark failed"
      pending={m.isPending}
      error={m.error}
      onApprove={(proof) => m.mutate({ decision: true, txnRef: proof || undefined, notes: proof ? `Txn/ref: ${proof}` : undefined })}
      onReject={(proof) => m.mutate({ decision: false, notes: proof })}
    />
  );
}
