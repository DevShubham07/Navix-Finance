"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { FileSignature, Loader2, Plus, RefreshCw, Trash2, Eye } from "lucide-react";
import {
  Badge,
  ConfirmDialog,
  Dialog,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EmptyState,
  ErrorState,
  Input,
  Select,
  Skeleton,
  toast,
} from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { PdfPreviewDialog } from "@/components/staff/pdf-preview-dialog";
import { esignDocsApi, storageApi } from "@/lib/api/applications";
import { DOC_STATUS_VARIANT, esignErrMessage } from "@/lib/esign/esign-ui";
import {
  MAX_PDF_BYTES,
  MAX_SIGNERS,
  POSITIONS,
  newSigner,
  pagesValue,
  validateEsignForm,
  type PagesMode,
  type SignerForm,
} from "@/lib/esign/validate-esign-form";

const PAGES_OPTIONS: Array<{ value: PagesMode; label: string }> = [
  { value: "All", label: "All pages" },
  { value: "First", label: "First page" },
  { value: "Last", label: "Last page" },
  { value: "Custom", label: "Custom pages…" },
];

function fmt(iso: string): string {
  return new Date(iso).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });
}

/** Admin · eSign documents — list + "New document" dialog. ADMIN only, behind the `esign-documents` flag. */
export default function AdminEsignPage() {
  const me = useStaffMe().data;
  const can = useCan();
  const q = useQuery({ queryKey: ["esign-docs"], queryFn: esignDocsApi.list });
  const [open, setOpen] = React.useState(false);

  if (me?.role && !can("staff:manage")) return <NoAccessNotice message="Admin access only." />;

  return (
    <div>
      <PageHeader title="eSign documents" subtitle="Send any PDF for Aadhaar eSign — to others, or sign it yourself. Admin only.">
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
        <button onClick={() => setOpen(true)} className="btn btn-sm btn-gold">
          <Plus size={14} /> New document
        </button>
      </PageHeader>

      {q.isLoading ? (
        <Skeleton variant="table" rows={6} cols={5} className="rounded border border-line bg-white" />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          <div className="staff-table-scroll">
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>Title</th>
                  <th>Signed</th>
                  <th>Status</th>
                  <th>Created</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {(q.data ?? []).map((d) => (
                  <tr key={d.id} className="hover:bg-grey-50">
                    <td>
                      <Link href={`/staff/admin/esign/${d.id}`} className="inline-flex items-center gap-1.5 font-medium text-navy hover:underline">
                        <FileSignature size={13} /> {d.title}
                      </Link>
                    </td>
                    <td className="text-muted">{d.signedCount} / {d.signerCount}</td>
                    <td><Badge variant={DOC_STATUS_VARIANT[d.status] ?? "neutral"}>{d.status}</Badge></td>
                    <td className="whitespace-nowrap text-muted">{fmt(d.createdAt)}</td>
                  </tr>
                ))}
                {(q.data ?? []).length === 0 && <EmptyState title="No documents sent for eSign yet." inTable={4} />}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {open && <NewDocumentDialog defaultName={me?.name ?? ""} onClose={() => setOpen(false)} />}
    </div>
  );
}

function NewDocumentDialog({ defaultName, onClose }: { defaultName: string; onClose: () => void }) {
  const router = useRouter();
  const qc = useQueryClient();
  const [file, setFile] = React.useState<File | null>(null);
  const [fileErr, setFileErr] = React.useState<string | null>(null);
  const [title, setTitle] = React.useState("");
  const [sendEmail, setSendEmail] = React.useState(true);
  const [signers, setSigners] = React.useState<SignerForm[]>([newSigner(0)]);
  const [previewUrl, setPreviewUrl] = React.useState<string | null>(null);
  const [showPreview, setShowPreview] = React.useState(false);
  const [confirm, setConfirm] = React.useState(false);
  const [showErrors, setShowErrors] = React.useState(false);

  React.useEffect(() => {
    if (!file) return setPreviewUrl(null);
    const u = URL.createObjectURL(file);
    setPreviewUrl(u);
    return () => URL.revokeObjectURL(u);
  }, [file]);

  const hasSelf = signers.some((s) => s.self);
  const errors = validateEsignForm({ title, hasFile: !!file, sendEmail, signers });

  const patch = (i: number, p: Partial<SignerForm>) =>
    setSigners((xs) => xs.map((s, j) => (j === i ? { ...s, ...p } : s)));

  const toggleSelf = (on: boolean) =>
    setSigners((xs) =>
      on
        ? [newSigner(0, true, defaultName), ...xs.slice(0, MAX_SIGNERS - 1)]
        : xs.filter((s) => !s.self),
    );

  const create = useMutation({
    mutationFn: async () => {
      const f = file!;
      const up = await storageApi.presignUpload({
        category: "ESIGN_DOCUMENT",
        filename: f.name,
        contentType: "application/pdf",
      });
      await storageApi.putToPresignedUrl(up.url, f);
      return esignDocsApi.create({
        title: title.trim(),
        objectKey: up.key,
        fileName: f.name,
        sendEmail,
        signers: signers.map((s) => ({
          name: s.name.trim(),
          email: s.self ? null : s.email.trim() || null,
          self: s.self,
          pages: pagesValue(s),
          position: s.position,
        })),
      });
    },
    onSuccess: (d) => {
      void qc.invalidateQueries({ queryKey: ["esign-docs"] });
      toast.success("Sent for eSign");
      router.push(`/staff/admin/esign/${d.id}`);
    },
    onError: () => setConfirm(false),
  });

  const onFile = (f: File | null) => {
    setFileErr(null);
    if (f && f.type !== "application/pdf" && !f.name.toLowerCase().endsWith(".pdf")) {
      setFileErr("Only PDF files are accepted.");
      return setFile(null);
    }
    if (f && f.size > MAX_PDF_BYTES) {
      setFileErr("PDF is larger than 10 MB.");
      return setFile(null);
    }
    setFile(f);
    if (f && !title) setTitle(f.name.replace(/\.pdf$/i, ""));
  };

  return (
    <>
      <Dialog open={!confirm} onClose={create.isPending ? () => {} : onClose} size="lg" aria-label="New eSign document">
        <DialogHeader>
          <DialogTitle>New eSign document</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-semibold text-ink">PDF (max 10 MB)</label>
            <input
              type="file"
              accept="application/pdf"
              onChange={(e) => onFile(e.target.files?.[0] ?? null)}
              className="block w-full text-sm text-muted file:mr-3 file:rounded file:border file:border-line file:bg-grey-50 file:px-3 file:py-1.5 file:text-sm file:font-semibold file:text-navy hover:file:bg-grey-100"
            />
            {fileErr && <p className="mt-1 text-sm text-error-700">{fileErr}</p>}
          </div>
          <Input label="Title" required value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Vendor agreement — Acme" />

          <label className="flex items-center gap-2 text-sm font-semibold text-ink">
            <input type="checkbox" checked={hasSelf} onChange={(e) => toggleSelf(e.target.checked)} />
            I will sign this myself
          </label>

          {signers.map((s, i) => (
            <div key={i} className="rounded border border-line p-3">
              <div className="mb-2 flex items-center justify-between text-xs font-semibold uppercase tracking-wide text-muted">
                <span>{s.self ? "You" : `Signer ${i + 1}`}</span>
                {!s.self && signers.length > 1 && (
                  <button
                    type="button"
                    aria-label={`Remove signer ${i + 1}`}
                    onClick={() => setSigners((xs) => xs.filter((_, j) => j !== i))}
                    className="text-muted hover:text-ink"
                  >
                    <Trash2 size={14} />
                  </button>
                )}
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                <Input
                  label="Name exactly as on Aadhaar"
                  required
                  value={s.name}
                  onChange={(e) => patch(i, { name: e.target.value })}
                  helperText="A mismatch fails the signature and wastes a billable contract."
                />
                {!s.self && (
                  <Input
                    label={sendEmail ? "Email" : "Email (optional)"}
                    type="email"
                    required={sendEmail}
                    value={s.email}
                    onChange={(e) => patch(i, { email: e.target.value })}
                  />
                )}
                <Select
                  label="Pages"
                  value={s.pagesMode}
                  onChange={(e) => patch(i, { pagesMode: e.target.value as PagesMode })}
                  options={PAGES_OPTIONS}
                />
                {s.pagesMode === "Custom" ? (
                  <Input
                    label="Page numbers"
                    value={s.pagesCustom}
                    onChange={(e) => patch(i, { pagesCustom: e.target.value })}
                    placeholder="1,3"
                  />
                ) : (
                  <div className="hidden sm:block" />
                )}
                <Select
                  label="Signature position"
                  value={s.position}
                  onChange={(e) => patch(i, { position: e.target.value })}
                  options={POSITIONS.map((p) => ({ value: p.value, label: p.label }))}
                />
              </div>
            </div>
          ))}

          <div className="flex flex-wrap items-center gap-3">
            <button
              type="button"
              className="btn btn-sm btn-outline"
              disabled={signers.length >= MAX_SIGNERS}
              onClick={() => setSigners((xs) => [...xs, newSigner(xs.length)])}
            >
              <Plus size={13} /> Add signer
            </button>
            <span className="text-xs text-muted">{signers.length} / {MAX_SIGNERS} signers</span>
          </div>

          <label className="flex items-center gap-2 text-sm text-ink">
            <input type="checkbox" checked={sendEmail} onChange={(e) => setSendEmail(e.target.checked)} />
            Email signing links to external signers
          </label>

          {showErrors && errors.length > 0 && (
            <ul className="list-disc pl-5 text-sm text-error-700">
              {errors.map((e) => <li key={e}>{e}</li>)}
            </ul>
          )}
          {create.error && <p className="text-sm text-error-700">{esignErrMessage(create.error)}</p>}
        </div>
        <DialogFooter>
          <button type="button" className="btn btn-sm btn-outline" onClick={onClose} disabled={create.isPending}>Cancel</button>
          <button type="button" className="btn btn-sm btn-outline" disabled={!previewUrl} onClick={() => setShowPreview(true)}>
            <Eye size={13} /> Preview
          </button>
          <button
            type="button"
            className="btn btn-sm btn-navy"
            onClick={() => (errors.length ? setShowErrors(true) : setConfirm(true))}
          >
            Review &amp; send
          </button>
        </DialogFooter>
      </Dialog>

      <PdfPreviewDialog open={showPreview} onClose={() => setShowPreview(false)} url={previewUrl} title={title || "Preview"} />

      <ConfirmDialog
        open={confirm}
        onClose={() => setConfirm(false)}
        onConfirm={() => create.mutate()}
        busy={create.isPending}
        title="Send for eSign?"
        confirmLabel="Create contract"
        body={
          <p>
            This creates a <strong>real, billable, legally binding Aadhaar eSign contract</strong> with {signers.length}{" "}
            signer{signers.length === 1 ? "" : "s"}. Check each name matches the signer&apos;s Aadhaar exactly.
          </p>
        }
      />
    </>
  );
}
