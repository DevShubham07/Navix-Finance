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
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Check, X, Loader2, RefreshCw, ChevronDown, FileText, Download, ExternalLink, User } from "lucide-react";
import { Input } from "@/components/ui";
import type { StaffRole } from "@/lib/auth/rbac";
import {
  staffApi,
  paiseToINR,
  statusLabel,
  openDocument,
  ApplicationApiError,
  type ApplicationStatus,
  type ApplicationView,
  type EventView,
  type DocumentView,
} from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

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
  if (e instanceof ApplicationApiError) return `${e.message} (${e.code})`;
  return e instanceof Error ? e.message : "Action failed.";
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
}: {
  title: string;
  status: ApplicationStatus;
  actions: (app: ApplicationView) => React.ReactNode;
}) {
  const q = useQuery({
    queryKey: ["staff-queue", status],
    queryFn: () => staffApi.listByStatus(status),
    refetchInterval: 8000,
  });

  return (
    <QueuePanel
      title={title}
      countBadge={status}
      apps={q.data ?? []}
      isLoading={q.isLoading}
      error={q.error}
      onRefresh={() => q.refetch()}
      actions={actions}
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
}: {
  title: string;
  countBadge: string;
  apps: ApplicationView[];
  isLoading: boolean;
  error: unknown;
  onRefresh: () => void;
  actions: (app: ApplicationView) => React.ReactNode;
}) {
  return (
    <section className="rounded border border-line bg-white shadow-sm">
      <header className="flex items-center justify-between gap-3 border-b border-line px-5 py-3">
        <div className="flex items-center gap-2">
          <h2 className="font-serif text-lg font-semibold text-navy">{title}</h2>
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
  const [showEvents, setShowEvents] = React.useState(false);
  return (
    <li className="px-5 py-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="font-serif text-base font-semibold text-navy">
            Application #{app.id}
            <span className="ml-2 align-middle text-xs font-normal text-muted">applicant #{app.applicantId}</span>
          </div>
          <div className="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-muted">
            <span className="rounded-full bg-navy-tint px-2 py-0.5 font-semibold text-navy">{statusLabel(app.status)}</span>
            <span>Requested {paiseToINR(app.amountRequestedPaise)}</span>
            {app.assignedExecutiveId != null && <span>· exec #{app.assignedExecutiveId}</span>}
            {app.loanId != null && <span>· loan #{app.loanId}</span>}
          </div>
        </div>
        <div className="flex items-center gap-2">{actions(app)}</div>
      </div>

      <div className="mt-3">
        <ApplicantReview applicationId={app.id} />
      </div>

      <button
        onClick={() => setShowEvents((o) => !o)}
        className="mt-2 flex items-center gap-1 text-xs font-semibold text-navy hover:underline"
      >
        <ChevronDown size={13} className={showEvents ? "rotate-180 transition" : "transition"} />
        {showEvents ? "Hide" : "Show"} maker-checker trail
      </button>
      {showEvents && <EventsTrail id={app.id} />}
    </li>
  );
}

function EventsTrail({ id }: { id: number }) {
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
          <span className="flex-shrink-0 text-muted">{e.at ? formatDate(e.at) : ""}</span>
        </li>
      ))}
    </ul>
  );
}

// ---------------------------------------------------------------------------
// Applicant review: KYC details + documents (any reviewing role)
// ---------------------------------------------------------------------------

export function ApplicantReview({ applicationId }: { applicationId: number }) {
  const [load, setLoad] = React.useState(false);
  const profileQ = useQuery({
    queryKey: ["staff-profile", applicationId],
    queryFn: () => staffApi.getProfile(applicationId),
    enabled: load,
    retry: false,
  });
  const docsQ = useQuery({
    queryKey: ["staff-docs", applicationId],
    queryFn: () => staffApi.documents(applicationId),
    enabled: load,
    retry: false,
  });

  if (!load) {
    return (
      <button onClick={() => setLoad(true)} className="btn btn-sm btn-outline">
        <User size={14} /> Show applicant details &amp; documents
      </button>
    );
  }

  const p = profileQ.data;

  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 font-serif text-base font-semibold text-navy">
        <User size={16} /> Applicant details
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
          <Row label="PAN" value={p.panMasked} mono />
          <Row label="Date of birth" value={p.dob} />
          <Row label="Employer" value={p.employer} />
          <Row label="Employment" value={p.employmentStatus} />
          <Row label="Monthly salary" value={p.monthlySalaryPaise != null ? paiseToINR(p.monthlySalaryPaise) : null} />
          <Row label="Salary bank" value={p.salaryBank} />
          <Row label="Address" value={p.address} />
        </dl>
      )}

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
  );
}

function Row({ label, value, mono }: { label: string; value: string | null | undefined; mono?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4 py-2">
      <dt className="text-muted">{label}</dt>
      <dd className={mono ? "text-right font-mono text-ink" : "text-right text-ink"}>{value || "—"}</dd>
    </div>
  );
}

/** Universal: every staff role can open any application by ID and load its applicant review on demand. */
export function ReviewLookup() {
  const [input, setInput] = React.useState("");
  const [openId, setOpenId] = React.useState<number | null>(null);
  return (
    <section className="rounded border border-line bg-white shadow-sm">
      <header className="border-b border-line px-5 py-3">
        <h2 className="font-serif text-lg font-semibold text-navy">Review an application</h2>
        <p className="mt-0.5 text-xs text-muted">
          Open any application by its ID to view the applicant&apos;s details and documents — loaded on demand.
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
          <ApplicantReview key={openId} applicationId={openId} />
        </div>
      )}
    </section>
  );
}

function DocRow({ appId, doc }: { appId: number; doc: DocumentView }) {
  const [busy, setBusy] = React.useState<null | "view" | "download">(null);
  const [err, setErr] = React.useState<string | null>(null);

  const fetchAnd = async (mode: "view" | "download") => {
    setBusy(mode);
    setErr(null);
    try {
      const content = await staffApi.document(appId, doc.id);
      openDocument(content, mode === "download");
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

export function KycActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) => staffApi.kycDecision(app.id, decision),
    onSuccess: () => refresh(app.id),
  });
  return (
    <div className="flex items-center gap-2">
      <ApproveRejectButtons pending={m.isPending} onApprove={() => m.mutate(true)} onReject={() => m.mutate(false)} />
      <ActionError error={m.error} />
    </div>
  );
}

export function AssignActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const [execId, setExecId] = React.useState("101");
  const m = useMutation({
    mutationFn: () => staffApi.assign(app.id, Number.parseInt(execId || "101", 10)),
    onSuccess: () => refresh(app.id),
  });
  return (
    <div className="flex items-center gap-2">
      <Input
        aria-label="Executive id"
        value={execId}
        onChange={(e) => setExecId(e.target.value.replace(/\D/g, ""))}
        inputClassName="w-20"
        className="!mb-0"
        placeholder="exec id"
      />
      <button onClick={() => m.mutate()} disabled={m.isPending || !execId} className="btn btn-sm btn-navy disabled:opacity-50">
        {m.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Assign
      </button>
      <ActionError error={m.error} />
    </div>
  );
}

export function ExecActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) => staffApi.execDecision(app.id, decision),
    onSuccess: () => refresh(app.id),
  });
  return (
    <div className="flex items-center gap-2">
      <ApproveRejectButtons pending={m.isPending} onApprove={() => m.mutate(true)} onReject={() => m.mutate(false)} />
      <ActionError error={m.error} />
    </div>
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
    <div className="flex items-center gap-2">
      <ApproveRejectButtons pending={m.isPending} onApprove={() => m.mutate(true)} onReject={() => m.mutate(false)} />
      <ActionError error={m.error} />
    </div>
  );
}

export function DisbursementActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) => staffApi.disbursementDecision(app.id, decision),
    onSuccess: () => refresh(app.id),
  });
  return (
    <div className="flex items-center gap-2">
      <ApproveRejectButtons
        pending={m.isPending}
        onApprove={() => m.mutate(true)}
        onReject={() => m.mutate(false)}
        approveLabel="Accept"
      />
      <ActionError error={m.error} />
    </div>
  );
}

export function AccountantActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) => staffApi.accountantValidate(app.id, decision),
    onSuccess: () => refresh(app.id),
  });
  return (
    <div className="flex items-center gap-2">
      <ApproveRejectButtons
        pending={m.isPending}
        onApprove={() => m.mutate(true)}
        onReject={() => m.mutate(false)}
        approveLabel="Validate success"
        rejectLabel="Mark failed"
      />
      <ActionError error={m.error} />
    </div>
  );
}
