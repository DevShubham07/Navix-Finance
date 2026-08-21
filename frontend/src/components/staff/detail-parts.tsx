"use client";

/**
 * Shared building blocks for the staff detail popups (customer detail, application detail).
 *
 * Extracted verbatim from `customer-detail-dialog.tsx` (Phase G) so both dialogs can compose
 * the same documents/remarks tabs and presentational primitives instead of drifting apart.
 * `DocumentsTab` is application-scoped; `RemarksTab` is customer-scoped — both already matched
 * the shape the new application detail dialog needs, unchanged.
 */

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, Check, Upload, Trash2, FileText, ExternalLink, ChevronDown, ChevronRight } from "lucide-react";
import { useStaffSession } from "@/lib/auth/staff-session";
import { hasPermission } from "@/lib/auth/rbac";
import { formatDateTime } from "@/lib/utils";
import { InfoTooltip } from "@/components/ui/tooltip";
import {
  customersApi,
  staffApi,
  openDocument,
  fileToBase64,
  statusLabel,
  type DocumentView,
  type ApplicationDocumentGroup,
  type CallLogView,
} from "@/lib/api/applications";

export const CUSTOMER_LEVEL_DOC_TYPES = new Set([
  "AADHAAR",
  "AADHAAR_PHOTO",
  "SELFIE",
  "PAN",
  "ADDRESS",
  "SALARY",
  // The real persisted value is SALARY_SLIP (see ApplicationVerificationService#verifySalary) — the
  // former "PAYSLIP" entry here never matched any real document and fell through to the per-application
  // section. BANK_STATEMENT is the new 6-month bank-statement upload (bank-details page).
  "SALARY_SLIP",
  "BANK_STATEMENT",
]);

/** Friendly labels for docTypes shown to staff; unlisted types fall back to the raw string. */
export const DOC_TYPE_LABELS: Record<string, string> = {
  SALARY_SLIP: "Salary slip",
  BANK_STATEMENT: "Bank statement",
};

export function docTypeLabel(docType: string): string {
  return DOC_TYPE_LABELS[docType.toUpperCase()] ?? docType;
}

// ---------------------------------------------------------------------------
// Documents (admin replace = delete-then-upload)
// ---------------------------------------------------------------------------

/**
 * Either `applicationId` (today's behaviour — one application's documents) or `customerId`
 * (grouped mode: every application this customer ever filed, newest first, so uploads from a
 * prior application survive a reborrow instead of becoming unreachable). Exactly one is expected.
 */
export function DocumentsTab({
  applicationId,
  customerId,
}: {
  applicationId?: number;
  customerId?: number;
}) {
  if (customerId != null) return <GroupedDocumentsTab customerId={customerId} />;
  if (applicationId != null) return <SingleApplicationDocuments applicationId={applicationId} />;
  return <p className="py-6 text-sm text-muted">No application to attach documents to.</p>;
}

/** Customer identity proofs are grouped by type; loan-specific documents stay under applications. */
function GroupedDocumentsTab({ customerId }: { customerId: number }) {
  const qc = useQueryClient();
  const role = useStaffSession().session?.role;
  const isAdmin = role != null && hasPermission(role, "customer:manage");
  const groupsQ = useQuery({
    queryKey: ["customer-documents", customerId],
    queryFn: () => customersApi.documents(customerId),
  });
  const [openIds, setOpenIds] = React.useState<Set<number> | null>(null);
  const groups = groupsQ.data ?? [];
  const customerDocuments = groups.flatMap((group) =>
    group.documents
      .filter((doc) => CUSTOMER_LEVEL_DOC_TYPES.has(doc.docType.toUpperCase()))
      .map((doc) => ({ applicationId: group.applicationId, doc })),
  );
  const customerByType = customerDocuments.reduce((byType, entry) => {
    const docType = entry.doc.docType.toUpperCase();
    const current = byType.get(docType) ?? [];
    current.push(entry);
    byType.set(docType, current);
    return byType;
  }, new Map<string, typeof customerDocuments>());
  const applicationGroups = groups.map((group) => ({
    ...group,
    documents: group.documents.filter((doc) => !CUSTOMER_LEVEL_DOC_TYPES.has(doc.docType.toUpperCase())),
  }));
  const del = useMutation({
    mutationFn: ({ appId, docId }: { appId: number; docId: number }) => staffApi.deleteDocument(appId, docId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["customer-documents", customerId] }),
  });

  // Expand the newest application by default, once data arrives.
  const effectiveOpen = openIds ?? new Set(groups.length ? [groups[0].applicationId] : []);
  const toggle = (appId: number) => {
    const next = new Set(effectiveOpen);
    if (next.has(appId)) next.delete(appId);
    else next.add(appId);
    setOpenIds(next);
  };

  if (groupsQ.isLoading) return <p className="text-sm text-muted">Loading…</p>;
  if (groups.length === 0) return <p className="text-sm text-muted">No documents uploaded.</p>;

  return (
    <div className="space-y-2">
      {customerDocuments.length > 0 && (
        <Section title="Customer documents">
          <div className="space-y-4">
            {Array.from(customerByType.entries()).map(([docType, documents]) => (
              <div key={docType}>
                <h4 className="mb-1.5 text-xs font-semibold text-navy">{docTypeLabel(docType)}</h4>
                <ul className="space-y-1.5">
                  {documents.map(({ applicationId, doc }) => (
                    <DocRow
                      key={`${applicationId}-${doc.id}`}
                      appId={applicationId}
                      doc={doc}
                      sourceMeta={`Application #${applicationId} · uploaded ${formatDateTime(doc.uploadedAt)}`}
                      canDelete={isAdmin}
                      onDelete={() => del.mutate({ appId: applicationId, docId: doc.id })}
                      deleting={del.isPending && del.variables?.appId === applicationId && del.variables.docId === doc.id}
                    />
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </Section>
      )}

      <div className="text-xs font-semibold uppercase tracking-wide text-muted">Application documents</div>
      {applicationGroups.map((g) => (
        <div key={g.applicationId} className="rounded border border-line">
          <button
            type="button"
            onClick={() => toggle(g.applicationId)}
            className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left"
          >
            <span className="flex items-center gap-1.5 text-sm font-semibold text-ink">
              {effectiveOpen.has(g.applicationId) ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
              Application #{g.applicationId}
              <span className="font-normal text-muted"> · {statusLabel(g.applicationStatus)}</span>
            </span>
            <span className="rounded-full bg-navy-tint px-2 py-0.5 text-[8.8px] font-semibold text-navy">
              {g.documents.length}
            </span>
          </button>
          {effectiveOpen.has(g.applicationId) && (
            <div className="border-t border-line p-3">
              <SingleApplicationDocuments
                applicationId={g.applicationId}
                customerId={customerId}
                documents={g.documents}
                existingCategories={groups.find((group) => group.applicationId === g.applicationId)?.documents.map((doc) => doc.docType)}
              />
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

function SingleApplicationDocuments({
  applicationId,
  customerId,
  documents,
  existingCategories,
}: {
  applicationId: number;
  customerId?: number;
  documents?: DocumentView[];
  existingCategories?: string[];
}) {
  const qc = useQueryClient();
  const role = useStaffSession().session?.role;
  const isAdmin = role != null && hasPermission(role, "customer:manage");
  // Uploading is wider than deleting: ADMIN plus the two credit roles may attach a document
  // (a payslip or statement the borrower sent in out-of-band), but replacing borrower-submitted
  // evidence still means deleting it first, which stays ADMIN-only.
  const canUpload = role != null && hasPermission(role, "document:upload");
  const docsQ = useQuery({
    queryKey: ["staff-docs", applicationId],
    queryFn: () => staffApi.documents(applicationId),
    enabled: documents == null,
  });
  const del = useMutation({
    mutationFn: (docId: number) => staffApi.deleteDocument(applicationId, docId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["staff-docs", applicationId] });
      if (customerId != null) qc.invalidateQueries({ queryKey: ["customer-documents", customerId] });
    },
  });

  const docs = docsQ.data ?? documents ?? [];
  const categories = Array.from(new Set(existingCategories ?? docs.map((d) => d.docType)));

  return (
    <div className="space-y-3">
      {documents == null && docsQ.isLoading ? (
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

      {canUpload && (
        <DocumentUpload
          applicationId={applicationId}
          customerId={customerId}
          existingCategories={categories}
          canDelete={isAdmin}
        />
      )}
      {!canUpload && <p className="text-xs text-muted">Your role cannot upload or replace documents.</p>}
    </div>
  );
}

/**
 * The borrower-supplied key for a password-protected upload (V56). Bank statements and payslips
 * routinely arrive encrypted; without the key the reviewer cannot open the file at all. Rendered
 * plainly (and `select-all`) so it can be copied straight into the PDF reader's prompt.
 */
export function DocPassword({ password }: { password?: string | null }) {
  if (!password) return null;
  return (
    <span className="block text-xs text-muted">
      Password: <span className="select-all font-mono text-ink">{password}</span>
    </span>
  );
}

export function DocRow({
  appId,
  doc,
  canDelete,
  onDelete,
  deleting,
  sourceMeta,
}: {
  appId: number;
  doc: DocumentView;
  canDelete: boolean;
  onDelete: () => void;
  deleting: boolean;
  sourceMeta?: string;
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
      <span className="min-w-0 flex-1 text-ink">
        <span className="block">{doc.fileName}</span>
        {sourceMeta && <span className="block text-xs text-muted">{sourceMeta}</span>}
        <DocPassword password={doc.filePassword} />
      </span>
      <span className="rounded-full bg-navy-tint px-2 py-0.5 text-[8.8px] font-semibold text-navy">{docTypeLabel(doc.docType)}</span>
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
 * Reusable "every document of these docTypes, across all this customer's applications" list — used by
 * the customer-tabs.tsx Salary tab (SALARY_SLIP) and Bank Accounts tab (BANK_STATEMENT). Shares the
 * "customer-documents" query key with the Documents tab so it costs nothing extra once either has loaded.
 */
export function CustomerDocsByType({
  customerId,
  docTypes,
  emptyCopy,
}: {
  customerId: number;
  docTypes: Set<string>;
  emptyCopy: string;
}) {
  const groupsQ = useQuery({
    queryKey: ["customer-documents", customerId],
    queryFn: () => customersApi.documents(customerId),
  });
  const groups: ApplicationDocumentGroup[] = groupsQ.data ?? [];
  const rows = groups.flatMap((group) =>
    group.documents
      .filter((doc) => docTypes.has(doc.docType.toUpperCase()))
      .map((doc) => ({ applicationId: group.applicationId, doc })),
  );

  if (groupsQ.isLoading) return <p className="text-sm text-muted">Loading…</p>;
  if (rows.length === 0) return <p className="text-sm text-muted">{emptyCopy}</p>;

  return (
    <ul className="space-y-1.5">
      {rows.map(({ applicationId, doc }) => (
        <DocRow
          key={`${applicationId}-${doc.id}`}
          appId={applicationId}
          doc={doc}
          sourceMeta={`Application #${applicationId} · uploaded ${formatDateTime(doc.uploadedAt)}`}
          canDelete={false}
          onDelete={() => {}}
          deleting={false}
        />
      ))}
    </ul>
  );
}

/** The two "manual proof" fallback upload types this flag watches for. */
const MANUAL_REVIEW_DOC_TYPES = new Set(["BANK_PROOF", "AADHAAR_FRONT", "AADHAAR_BACK"]);

/**
 * Purely visual "needs manual review" flag — lit iff the customer has ever uploaded a cancelled
 * cheque/passbook (BANK_PROOF) or Aadhaar card photos (AADHAAR_FRONT/AADHAAR_BACK) as a fallback
 * for a failed penny drop or a DigiLocker that never connected. It carries no backend meaning: no
 * clearing logic, no gate, nothing to click — reviewing those documents happens on the
 * Verifications tab's existing per-check manual override.
 *
 * Deliberately derived from document PRESENCE, not the PENNY_DROP/AADHAAR verification rows'
 * `derived.bankProofPending`/`aadhaarProofPending` markers: those flip false the moment a reviewer
 * records ANY manual-override decision on that check, so a marker-driven flag would go dark the
 * instant someone acts on it — the opposite of what a durable "this file needed a fallback upload"
 * flag is for. Uploads are permanent, so this stays lit for the life of the file. Resist the urge
 * to "simplify" this to read the markers instead — that would silently reintroduce clearing.
 */
export function NeedsManualReviewBadge({ customerId, className }: { customerId: number; className?: string }) {
  const groupsQ = useQuery({
    queryKey: ["customer-documents", customerId],
    queryFn: () => customersApi.documents(customerId),
  });
  const flagged = (groupsQ.data ?? []).some((group) =>
    group.documents.some((doc) => MANUAL_REVIEW_DOC_TYPES.has(doc.docType.toUpperCase())),
  );
  if (!flagged) return null;

  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full bg-warning-100 px-2 py-0.5 font-semibold text-warning-800 ${className ?? ""}`}
    >
      Needs manual review
      <InfoTooltip
        content="Cheque or Aadhaar uploaded — verify manually on the Verifications tab."
        label="Why this needs manual review"
        size={12}
      />
    </span>
  );
}

/**
 * Staff upload control (ADMIN + the credit roles). The replace rule: a document of a category can
 * only be uploaded once the existing document of that category has been deleted — so if the chosen
 * category still has a doc, upload is blocked. Deleting is ADMIN-only, so the hint differs by role:
 * an admin is told to delete it above, a credit user is told to ask an administrator.
 */
function DocumentUpload({
  applicationId,
  customerId,
  existingCategories,
  canDelete,
}: {
  applicationId: number;
  customerId?: number;
  existingCategories: string[];
  canDelete: boolean;
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
      if (customerId != null) qc.invalidateQueries({ queryKey: ["customer-documents", customerId] });
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
        <input type="file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="text-xs" />
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
          A “{docType.trim()}” document already exists —{" "}
          {canDelete
            ? "delete it above before uploading a replacement."
            : "ask an administrator to delete it before a replacement can be uploaded."}
        </p>
      )}
      {up.error && !blocked && <p className="mt-1.5 text-xs text-error-700">Upload failed. Check the file and try again.</p>}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Remarks (customer-scoped)
// ---------------------------------------------------------------------------

export function RemarksTab({ customerId }: { customerId: number }) {
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
              <p className="mt-1 text-[8.8px] text-muted">
                {r.author ?? "staff"}
                {r.at ? ` · ${formatDateTime(r.at)}` : ""}
              </p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Small presentational primitives
// ---------------------------------------------------------------------------

export function Section({ title, children }: { title: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="rounded border border-line bg-white p-3">
      <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">{title}</div>
      {children}
    </div>
  );
}

export function KV({ k, v, mono }: { k: string; v: React.ReactNode; mono?: boolean }) {
  return (
    <div className="flex items-start justify-between gap-4 py-1">
      <dt className="flex-shrink-0 text-muted">{k}</dt>
      <dd className={mono ? "min-w-0 flex-1 break-all text-right font-mono text-ink" : "min-w-0 flex-1 break-all text-right text-ink"}>
        {v || "—"}
      </dd>
    </div>
  );
}

export function Bool({ on }: { on?: boolean | null }) {
  return on ? (
    <span className="inline-flex items-center gap-0.5 text-success-700">
      <Check size={13} /> Yes
    </span>
  ) : (
    <span className="text-muted">No</span>
  );
}

/**
 * One call-log row, shared verbatim between this tab and the loan detail modal's Calls tab
 * (`loan-detail-dialog.tsx`) so the two never render the same data two different ways. Shows
 * the loan tag whenever the log carries one — the modal filters rows before handing them here,
 * so a bare `Loan #id` is enough context in either place.
 */
export function CallLogRow({ log }: { log: CallLogView }) {
  return (
    <li className="rounded border border-line p-2.5">
      <p className="text-sm font-semibold text-ink">
        {log.callType} · {log.outcome}
        {log.callbackOn ? ` · callback ${log.callbackOn}` : ""}
        {log.loanId != null ? ` · Loan #${log.loanId}` : ""}
      </p>
      {log.notes && <p className="mt-1 whitespace-pre-wrap text-sm text-ink">{log.notes}</p>}
      <p className="mt-1 text-[8.8px] text-muted">
        {log.author ?? "staff"}
        {log.at ? ` · ${formatDateTime(log.at)}` : ""}
      </p>
    </li>
  );
}
