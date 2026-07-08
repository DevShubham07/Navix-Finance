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
import { useQuery } from "@tanstack/react-query";
import { Check, Loader2, FileText, Download, ExternalLink, User } from "lucide-react";
import { CreditProfileCard } from "@/components/staff/credit-profile-card";
import { VerificationChecksPanel } from "@/components/staff/verification-checks";
import { hasPermission } from "@/lib/auth/rbac";
import {
  staffApi,
  paiseToINR,
  openDocument,
  type DocumentView,
} from "@/lib/api/applications";
import { useStaffMe, errMessage, REVIEW_PERMS } from "@/components/staff/pipeline/hooks";
import { NoAccessNotice } from "@/components/staff/pipeline/actions";

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

      <VerificationChecksPanel applicationId={applicationId} />

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
