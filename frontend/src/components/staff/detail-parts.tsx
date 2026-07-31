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
import { Loader2, Check, Upload, Trash2, FileText, ExternalLink } from "lucide-react";
import { useStaffSession } from "@/lib/auth/staff-session";
import { hasPermission } from "@/lib/auth/rbac";
import { formatDateTime } from "@/lib/utils";
import {
  customersApi,
  staffApi,
  openDocument,
  fileToBase64,
  type DocumentView,
} from "@/lib/api/applications";

// ---------------------------------------------------------------------------
// Documents (admin replace = delete-then-upload)
// ---------------------------------------------------------------------------

export function DocumentsTab({ applicationId }: { applicationId: number }) {
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

      {isAdmin && <AdminUpload applicationId={applicationId} existingCategories={categories} />}
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
          A “{docType.trim()}” document already exists — delete it above before uploading a replacement.
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
              <p className="mt-1 text-[11px] text-muted">
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

export function Section({ title, children }: { title: string; children: React.ReactNode }) {
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
