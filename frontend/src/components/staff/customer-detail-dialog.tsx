"use client";

/**
 * Unified customer detail popup — a persistent tabbed dialog that replaces the old page-navigation
 * and inline "expand" drilldowns across the staff CRM (customers, all-applications, kyc-approvals).
 *
 * It is "continued context": the parent keeps it mounted and swaps `customerId`, so the active tab
 * persists as staff click through rows. Six tabs: Basic details · Past details · Documents ·
 * All past logs · Remarks · More options. Data-dense on purpose.
 */

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, Check, X, Upload, Trash2, FileText, ExternalLink } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { Tabs, type TabDef } from "@/components/ui/tabs";
import { useStaffSession } from "@/lib/auth/staff-session";
import { hasPermission } from "@/lib/auth/rbac";
import { formatDate, formatDateTime } from "@/lib/utils";
import {
  customersApi,
  staffApi,
  paiseToINR,
  statusLabel,
  openDocument,
  type CustomerDetail,
  type LoanView,
  type ApplicationView,
  type DocumentView,
  type ActivityEntry,
} from "@/lib/api/applications";

const PROCESSING_FEE_RATE = 0.1;
const GST_RATE = 0.18;
const DAILY_INTEREST_RATE = 0.01;

export function CustomerDetailDialog({
  customerId,
  onClose,
}: {
  customerId: number | null;
  onClose: () => void;
}) {
  const [tab, setTab] = React.useState("basic");
  const open = customerId != null;

  const detailQ = useQuery({
    queryKey: ["customer-detail", customerId],
    queryFn: () => customersApi.get(customerId as number),
    enabled: open,
  });

  const c = detailQ.data;
  const latestAppId = c?.applications[0]?.id ?? null;

  const tabs: TabDef[] = [
    { key: "basic", label: "Basic details" },
    { key: "past", label: "Past details", badge: c ? c.applications.length + c.loans.length : undefined },
    { key: "documents", label: "Documents" },
    { key: "logs", label: "All past logs" },
    { key: "remarks", label: "Remarks" },
    { key: "more", label: "More options" },
  ];

  // !max-w-4xl / !w-[...]: globals.css's un-layered `.modal { max-width: 460px }` outranks
  // plain utilities in the cascade, so the width needs the important modifier (mirrors
  // stage-detail-dialog.tsx:178).
  return (
    <Dialog open={open} onClose={onClose} className="!max-w-4xl !w-[min(56rem,94vw)]">
      <div className="flex items-center justify-between gap-3 border-b border-line pb-3">
        <div>
          <h3 className="font-serif text-lg text-navy">
            {c?.profile?.fullName ?? "Customer"}{" "}
            <span className="text-sm font-normal text-muted">#{customerId}</span>
          </h3>
          {c?.profile && (
            <p className="text-xs text-muted">
              {c.profile.mobile ?? "—"} · PAN {c.profile.pan ?? "—"}
              {c.profile.riskCategory ? ` · risk ${c.profile.riskCategory}` : ""}
            </p>
          )}
        </div>
        <button onClick={onClose} className="rounded p-1 text-muted hover:bg-grey-100 hover:text-ink" aria-label="Close">
          <X size={18} />
        </button>
      </div>

      <Tabs tabs={tabs} active={tab} onChange={setTab} className="mt-2" />

      <div className="mt-3 max-h-[68vh] overflow-y-auto pr-1 text-[13px]">
        {detailQ.isLoading ? (
          <p className="flex items-center gap-2 py-8 text-sm text-muted">
            <Loader2 size={15} className="animate-spin" /> Loading…
          </p>
        ) : detailQ.error ? (
          <p className="py-8 text-sm text-error-700">Could not load this customer.</p>
        ) : !c ? null : (
          <>
            {tab === "basic" && <BasicTab c={c} />}
            {tab === "past" && <PastTab c={c} />}
            {tab === "documents" && latestAppId != null && (
              <DocumentsTab applicationId={latestAppId} />
            )}
            {tab === "documents" && latestAppId == null && (
              <p className="py-6 text-sm text-muted">No application to attach documents to.</p>
            )}
            {tab === "logs" && customerId != null && <LogsTab customerId={customerId} />}
            {tab === "remarks" && customerId != null && <RemarksTab customerId={customerId} />}
            {tab === "more" && <MoreTab c={c} />}
          </>
        )}
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------------------
// Tab 1 — Basic details
// ---------------------------------------------------------------------------

function BasicTab({ c }: { c: CustomerDetail }) {
  const p = c.profile;
  const currentLoan = c.loans.find((l) => ["ACTIVE", "OVERDUE", "DISBURSED", "IN_COLLECTIONS", "DEFAULTED"].includes(l.status)) ?? c.loans[0] ?? null;
  const latestApp = c.applications[0] ?? null;
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Section title="Identity & profile">
        <KV k="Full name" v={p?.fullName} />
        <KV k="PAN" v={p?.pan} mono />
        <KV k="Mobile" v={p?.mobile} mono />
        <KV k="Email" v={p?.email} />
        <KV k="Date of birth" v={p?.dob} />
        <KV k="Address" v={p?.address} />
        <KV k="Employer" v={p?.employer} />
        <KV k="Employment" v={p?.employmentStatus} />
        <KV k="Salary bank" v={p?.salaryBank} />
      </Section>

      <Section title="Verification & credit">
        {/* verification block */}
        <KV k="PAN verified" v={<Bool on={p?.panVerified} />} />
        <KV k="Aadhaar (DigiLocker)" v={<Bool on={p?.aadhaarVerified} />} />
        <KV k="Aadhaar linked" v={<Bool on={p?.aadhaarLinked} />} />
        <KV k="Email verified" v={<Bool on={p?.emailVerified} />} />
        <KV k="Address verified" v={<Bool on={p?.addressVerified} />} />
        <KV k="Penny drop" v={<Bool on={p?.pennyDropVerified} />} />
        <KV k="Identity match" v={p?.nameMatchScore != null ? `${Math.round(p.nameMatchScore * 100)}%` : null} />
        {/* credit block */}
        <KV k="CIBIL score" v={p?.creditScore != null ? String(p.creditScore) : null} mono />
        <KV k="Star rating" v={p?.starRating != null ? `${p.starRating.toFixed(1)}★` : null} />
        <KV k="Recommendation" v={p?.recommendation} />
        <KV k="Risk category" v={p?.riskCategory} />
        <KV k="Bureau" v={p?.bureauSource} />
        <KV k="Credit brief summary" v={p?.creditBriefSummary} />
        <KV k="Credit brief generated" v={p?.creditBriefGeneratedAt ? formatDateTime(p.creditBriefGeneratedAt) : null} />
      </Section>

      <Section title="Salary & eligibility">
        <KV k="Monthly salary" v={p?.monthlySalaryPaise != null ? paiseToINR(p.monthlySalaryPaise) : null} />
        <KV k="Annual salary" v={p?.annualSalaryPaise != null ? paiseToINR(p.annualSalaryPaise) : null} />
        <KV k="Salary %" v={p?.salaryPercentage != null ? `${p.salaryPercentage}%` : null} />
        <KV k="Increment %" v={p?.incrementPercentage != null ? `${p.incrementPercentage}%` : null} />
        <KV k="Eligible limit" v={latestApp?.eligibleLimitPaise != null ? paiseToINR(latestApp.eligibleLimitPaise) : null} />
      </Section>

      <Section title="Emergency contact">
        <KV k="Name" v={p?.emergencyContactName} />
        <KV k="Phone" v={p?.emergencyContactPhone} mono />
        <KV k="Relation" v={p?.emergencyContactRelation} />
      </Section>

      <div className="md:col-span-2">
        <Section title="Loan cost calculation">
          {currentLoan ? (
            <CostBreakdown loan={currentLoan} />
          ) : latestApp?.amountRequestedPaise != null ? (
            <CostBreakdown amountPaise={latestApp.amountRequestedPaise} />
          ) : (
            <p className="text-sm text-muted">No loan or requested amount yet.</p>
          )}
        </Section>
      </div>
    </div>
  );
}

/** Fee / GST / interest / lent / returned breakdown from a disbursed loan or a requested amount. */
function CostBreakdown({ loan, amountPaise }: { loan?: LoanView; amountPaise?: number }) {
  const principal = loan?.principalPaise ?? amountPaise ?? 0;
  const fee = loan?.processingFeePaise ?? Math.round(principal * PROCESSING_FEE_RATE);
  const gst = loan?.gstPaise ?? Math.round(fee * GST_RATE);
  const net = loan?.netDisbursedPaise ?? principal - fee - gst;
  const total = loan?.totalRepayablePaise ?? null;
  return (
    <dl className="divide-y divide-line text-sm">
      <KV k="Loan (principal)" v={paiseToINR(principal)} mono />
      <KV k="Processing fee (10%)" v={`− ${paiseToINR(fee)}`} mono />
      <KV k="GST (18% of fee)" v={`− ${paiseToINR(gst)}`} mono />
      <KV k="Lent (net disbursed)" v={<span className="font-semibold text-navy">{paiseToINR(net)}</span>} mono />
      <KV k="Interest rate" v={`${((loan?.dailyInterestRate ?? DAILY_INTEREST_RATE) * 100).toFixed(0)}% / day`} />
      {total != null && <KV k="Returned (total repayable)" v={<span className="font-semibold text-navy">{paiseToINR(total)}</span>} mono />}
      {loan && <KV k="Outstanding" v={<span className="font-semibold text-error-700">{paiseToINR(loan.outstandingPaise)}</span>} mono />}
      {loan && <KV k="Due date" v={loan.dueDate ? formatDate(loan.dueDate) : null} />}
    </dl>
  );
}

// ---------------------------------------------------------------------------
// Tab 2 — Past details (applications, loans, payments)
// ---------------------------------------------------------------------------

function PastTab({ c }: { c: CustomerDetail }) {
  return (
    <div className="space-y-4">
      <Section title={`Applications (${c.applications.length})`}>
        {c.applications.length === 0 ? (
          <p className="text-sm text-muted">None.</p>
        ) : (
          <ul className="divide-y divide-line">
            {c.applications.map((a: ApplicationView) => (
              <li key={a.id} className="flex flex-wrap items-center justify-between gap-2 py-1.5">
                <span className="text-ink">
                  #{a.id} · {statusLabel(a.status)}
                  {a.purpose ? <span className="text-muted"> · {a.purpose}</span> : null}
                </span>
                <span className="font-mono text-muted">
                  {a.amountRequestedPaise != null ? paiseToINR(a.amountRequestedPaise) : "—"}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>

      <Section title={`Loans (${c.loans.length})`}>
        {c.loans.length === 0 ? (
          <p className="text-sm text-muted">None.</p>
        ) : (
          <div className="space-y-3">
            {c.loans.map((l) => (
              <div key={l.id} className="rounded border border-line p-3">
                <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
                  <span className="font-semibold text-navy">Loan #{l.id} · {paiseToINR(l.principalPaise)}</span>
                  <span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">{l.status}</span>
                </div>
                <CostBreakdown loan={l} />
              </div>
            ))}
          </div>
        )}
      </Section>

      <Section title={`Payments (${c.payments.length})`}>
        {c.payments.length === 0 ? (
          <p className="text-sm text-muted">None.</p>
        ) : (
          <ul className="divide-y divide-line">
            {c.payments.map((pm) => (
              <li key={pm.id} className="flex flex-wrap items-center justify-between gap-2 py-1.5">
                <span className="text-ink">
                  {paiseToINR(pm.amountPaise)} · {pm.method}
                  {pm.paidOn ? <span className="text-muted"> · {formatDate(pm.paidOn)}</span> : null}
                </span>
                <span className="rounded-full bg-grey-100 px-2 py-0.5 text-xs font-semibold text-muted">{pm.status}</span>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tab 3 — Documents (admin replace = delete-then-upload)
// ---------------------------------------------------------------------------

function DocumentsTab({ applicationId }: { applicationId: number }) {
  const qc = useQueryClient();
  const role = useStaffSession().session?.role;
  const isAdmin = role != null && hasPermission(role, "customer:manage");
  const docsQ = useQuery({
    queryKey: ["staff-docs", applicationId],
    queryFn: () => staffApi.documents(applicationId),
  });
  const del = useMutation({
    mutationFn: (docId: number) => staffApi.deleteDocument(applicationId, docId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["staff-docs", applicationId] }),
  });

  const docs = docsQ.data ?? [];
  const categories = Array.from(new Set(docs.map((d) => d.docType)));

  return (
    <div className="space-y-3">
      {docsQ.isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : docs.length === 0 ? (
        <p className="text-sm text-muted">No documents uploaded.</p>
      ) : (
        <ul className="space-y-1.5">
          {docs.map((d) => (
            <DocRow
              key={d.id}
              appId={applicationId}
              doc={d}
              canDelete={isAdmin}
              onDelete={() => del.mutate(d.id)}
              deleting={del.isPending && del.variables === d.id}
            />
          ))}
        </ul>
      )}
      {del.error && <p className="text-xs text-error-700">Could not delete the document.</p>}

      {isAdmin && (
        <AdminUpload applicationId={applicationId} existingCategories={categories} />
      )}
      {!isAdmin && <p className="text-xs text-muted">Only administrators can replace documents.</p>}
    </div>
  );
}

function DocRow({
  appId,
  doc,
  canDelete,
  onDelete,
  deleting,
}: {
  appId: number;
  doc: DocumentView;
  canDelete: boolean;
  onDelete: () => void;
  deleting: boolean;
}) {
  const [busy, setBusy] = React.useState(false);
  const view = async () => {
    setBusy(true);
    try {
      if (doc.s3) {
        const { url } = await staffApi.documentUrl(appId, doc.id);
        window.open(url, "_blank", "noopener,noreferrer");
      } else {
        openDocument(await staffApi.document(appId, doc.id), false);
      }
    } finally {
      setBusy(false);
    }
  };
  return (
    <li className="flex flex-wrap items-center gap-2 rounded border border-line px-3 py-2">
      <FileText size={15} className="flex-shrink-0 text-navy" />
      <span className="min-w-0 flex-1 truncate text-ink">{doc.fileName}</span>
      <span className="rounded-full bg-navy-tint px-2 py-0.5 text-[11px] font-semibold text-navy">{doc.docType}</span>
      <button onClick={view} disabled={busy} className="btn btn-sm btn-outline disabled:opacity-50">
        {busy ? <Loader2 size={13} className="animate-spin" /> : <ExternalLink size={13} />} View
      </button>
      {canDelete && (
        <button
          onClick={onDelete}
          disabled={deleting}
          className="btn btn-sm border-error-600 text-error-700 hover:bg-error-50 disabled:opacity-50"
          title="Delete this document (required before uploading a replacement)"
        >
          {deleting ? <Loader2 size={13} className="animate-spin" /> : <Trash2 size={13} />} Delete
        </button>
      )}
    </li>
  );
}

/**
 * Admin upload control. The replace rule: a document of a category can only be uploaded once the
 * existing document of that category has been deleted — so if the chosen category still has a doc,
 * upload is blocked with a hint to delete it first.
 */
function AdminUpload({
  applicationId,
  existingCategories,
}: {
  applicationId: number;
  existingCategories: string[];
}) {
  const qc = useQueryClient();
  const [docType, setDocType] = React.useState("");
  const [file, setFile] = React.useState<File | null>(null);
  const blocked = docType.trim() !== "" && existingCategories.includes(docType.trim());

  const up = useMutation({
    mutationFn: async () => {
      if (!file) throw new Error("Choose a file");
      const dataBase64 = await fileToBase64(file);
      return staffApi.uploadDocument(applicationId, {
        docType: docType.trim(),
        fileName: file.name,
        contentType: file.type || undefined,
        dataBase64,
      });
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["staff-docs", applicationId] });
      setFile(null);
      setDocType("");
    },
  });

  return (
    <div className="mt-2 rounded border border-dashed border-line bg-grey-50 p-3">
      <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">Upload / replace a document</div>
      <div className="flex flex-wrap items-center gap-2">
        <input
          value={docType}
          onChange={(e) => setDocType(e.target.value.toUpperCase())}
          placeholder="Category e.g. PAN, PAYSLIP"
          className="rounded border border-line px-2 py-1 text-sm"
        />
        <input
          type="file"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          className="text-xs"
        />
        <button
          onClick={() => up.mutate()}
          disabled={up.isPending || !file || docType.trim() === "" || blocked}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          {up.isPending ? <Loader2 size={13} className="animate-spin" /> : <Upload size={13} />} Upload
        </button>
      </div>
      {blocked && (
        <p className="mt-1.5 text-xs text-error-700">
          A “{docType.trim()}” document already exists — delete it above before uploading a replacement.
        </p>
      )}
      {up.error && !blocked && <p className="mt-1.5 text-xs text-error-700">Upload failed. Check the file and try again.</p>}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tab 4 — Activity timeline
// ---------------------------------------------------------------------------

const TYPE_STYLE: Record<string, string> = {
  LIFECYCLE: "bg-navy-tint text-navy",
  PROFILE: "bg-gold-50 text-gold-dark",
  REVERIFY: "bg-warning-100 text-warning-800",
  REMARK: "bg-grey-100 text-muted",
};

function LogsTab({ customerId }: { customerId: number }) {
  const q = useQuery({
    queryKey: ["customer-activity", customerId],
    queryFn: () => customersApi.activity(customerId),
  });
  if (q.isLoading) return <p className="text-sm text-muted">Loading…</p>;
  const items = q.data ?? [];
  if (items.length === 0) return <p className="text-sm text-muted">No activity recorded yet.</p>;
  return (
    <ul className="space-y-2">
      {items.map((e: ActivityEntry, i) => (
        <li key={i} className="flex gap-3 border-b border-line pb-2 last:border-0">
          <span className={`mt-0.5 h-fit rounded-full px-1.5 py-0.5 text-[10px] font-semibold ${TYPE_STYLE[e.type] ?? "bg-grey-100 text-muted"}`}>
            {e.type}
          </span>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <span className="font-semibold text-ink">
                {e.title}
                {e.applicationId != null ? <span className="font-normal text-muted"> · app #{e.applicationId}</span> : null}
              </span>
              <span className="text-[11px] text-muted">{e.at ? formatDateTime(e.at) : ""}</span>
            </div>
            {e.detail && <p className="break-words text-xs text-muted">{e.detail}</p>}
            {e.actor && <p className="text-[11px] text-muted">by {e.actor}</p>}
          </div>
        </li>
      ))}
    </ul>
  );
}

// ---------------------------------------------------------------------------
// Tab 5 — Remarks
// ---------------------------------------------------------------------------

function RemarksTab({ customerId }: { customerId: number }) {
  const qc = useQueryClient();
  const [body, setBody] = React.useState("");
  const q = useQuery({
    queryKey: ["customer-remarks", customerId],
    queryFn: () => customersApi.remarks(customerId),
  });
  const add = useMutation({
    mutationFn: () => customersApi.addRemark(customerId, body.trim()),
    onSuccess: () => {
      setBody("");
      qc.invalidateQueries({ queryKey: ["customer-remarks", customerId] });
      qc.invalidateQueries({ queryKey: ["customer-activity", customerId] });
    },
  });
  const remarks = q.data ?? [];
  return (
    <div className="space-y-3">
      <div className="flex flex-col gap-2">
        <textarea
          value={body}
          onChange={(e) => setBody(e.target.value)}
          rows={3}
          placeholder="Add a remark about this customer…"
          className="w-full rounded border border-line px-3 py-2 text-sm"
        />
        <div>
          <button
            onClick={() => add.mutate()}
            disabled={add.isPending || body.trim() === ""}
            className="btn btn-sm btn-navy disabled:opacity-50"
          >
            {add.isPending ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />} Add remark
          </button>
        </div>
      </div>
      {q.isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : remarks.length === 0 ? (
        <p className="text-sm text-muted">No remarks yet.</p>
      ) : (
        <ul className="space-y-2">
          {remarks.map((r) => (
            <li key={r.id} className="rounded border border-line p-2.5">
              <p className="whitespace-pre-wrap text-sm text-ink">{r.body}</p>
              <p className="mt-1 text-[11px] text-muted">
                {r.author ?? "staff"}{r.at ? ` · ${formatDateTime(r.at)}` : ""}
              </p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tab 6 — More options
// ---------------------------------------------------------------------------

function MoreTab({ c }: { c: CustomerDetail }) {
  const totalOutstanding = c.loans.reduce((s, l) => s + l.outstandingPaise, 0);
  const latestStatus = c.applications[0]?.status;
  return (
    <div className="space-y-4">
      <Section title="Summary">
        <KV k="Customer id" v={String(c.customerId)} mono />
        <KV k="Applications" v={String(c.applications.length)} />
        <KV k="Loans" v={String(c.loans.length)} />
        <KV k="Payments" v={String(c.payments.length)} />
        <KV k="Total outstanding" v={paiseToINR(totalOutstanding)} mono />
        <KV k="Latest status" v={latestStatus ? statusLabel(latestStatus) : null} />
      </Section>
      <Section title="Admin tools">
        <p className="text-sm text-muted">
          Salary/KYC correction, blocklist and lifecycle actions for this customer are available on the
          full customer page.
        </p>
        <a
          href={`/staff/customers/${c.customerId}`}
          className="mt-2 inline-flex items-center gap-1 text-sm font-semibold text-navy hover:underline"
        >
          Open full customer page <ExternalLink size={13} />
        </a>
      </Section>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Small presentational helpers
// ---------------------------------------------------------------------------

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="rounded border border-line bg-white p-3">
      <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">{title}</div>
      {children}
    </div>
  );
}

function KV({ k, v, mono }: { k: string; v: React.ReactNode; mono?: boolean }) {
  return (
    <div className="flex items-start justify-between gap-4 py-1">
      <dt className="flex-shrink-0 text-muted">{k}</dt>
      <dd className={mono ? "min-w-0 flex-1 break-all text-right font-mono text-ink" : "min-w-0 flex-1 break-all text-right text-ink"}>
        {v || "—"}
      </dd>
    </div>
  );
}

function Bool({ on }: { on?: boolean | null }) {
  return on ? (
    <span className="inline-flex items-center gap-0.5 text-success-700"><Check size={13} /> Yes</span>
  ) : (
    <span className="text-muted">No</span>
  );
}

async function fileToBase64(file: File): Promise<string> {
  const buf = await file.arrayBuffer();
  let binary = "";
  const bytes = new Uint8Array(buf);
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary);
}
