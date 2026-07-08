"use client";

/**
 * Customer review: KYC details + verification checks + documents (any reviewing role).
 *
 * Loaded on demand so a queue of N rows doesn't fan out N profile/document fetches.
 * Gated to the reviewer roles that have a need-to-know for borrower PII
 * ({@link REVIEW_PERMS}); collection roles / DEVELOPER get a NoAccessNotice. Used by
 * the unified credit detail page and the review lookup (and Phase F). Moved verbatim
 * from the former `live-pipeline.tsx` god-file — logic unchanged.
 */

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Check, Loader2, FileText, Download, ExternalLink, User, Bell } from "lucide-react";
import { CreditProfileCard } from "@/components/staff/credit-profile-card";
import { hasPermission } from "@/lib/auth/rbac";
import {
  staffApi,
  paiseToINR,
  openDocument,
  type DocumentView,
  type StepResult,
  type CheckStatus,
} from "@/lib/api/applications";
import { useStaffMe, errMessage, REVIEW_PERMS } from "@/components/staff/pipeline/hooks";
import { PermissionGate, NoAccessNotice } from "@/components/staff/pipeline/actions";

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
            mono
            value={
              p.aadhaar || p.aadhaarVerified ? (
                <span className="inline-flex items-center justify-end gap-1.5">
                  {p.aadhaar || "—"} {p.aadhaarVerified && <VerifiedPill />}
                </span>
              ) : null
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
