"use client";

/**
 * ADMIN "Allow re-application": lets a customer who is barred from re-applying (too-late repayment
 * history) start one new application that goes to the normal credit queue. Needs a reason and a
 * supporting document — picked from the customer's existing documents or uploaded here against their
 * latest application. Renders nothing unless the customer is disqualified with no unused allowance.
 */

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, RotateCcw } from "lucide-react";
import { Select, toast } from "@/components/ui";
import { customersApi, fileToBase64, staffApi } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

export function ReapplyAllowanceCard({
  customerId,
  latestApplicationId,
}: {
  customerId: number;
  /** The application a new upload attaches to; null disables uploading (picking still works). */
  latestApplicationId: number | null;
}) {
  const qc = useQueryClient();
  const statusQ = useQuery({
    queryKey: ["reapply-allowance", customerId],
    queryFn: () => customersApi.reapplyAllowance(customerId),
  });
  const docsQ = useQuery({
    queryKey: ["customer-documents", customerId],
    queryFn: () => customersApi.documents(customerId),
    enabled: Boolean(statusQ.data?.disqualified && !statusQ.data?.allowance),
  });
  const [reason, setReason] = React.useState("");
  const [docId, setDocId] = React.useState("");
  const [file, setFile] = React.useState<File | null>(null);

  const docs = (docsQ.data ?? []).flatMap((g) =>
    g.documents.map((d) => ({ id: d.id, label: `${d.docType} — ${d.fileName} (application #${g.applicationId})` })),
  );

  const grant = useMutation({
    mutationFn: async () => {
      let documentId = Number(docId);
      if (file) {
        if (latestApplicationId == null) throw new Error("This customer has no application to attach a file to.");
        const up = await staffApi.uploadDocument(latestApplicationId, {
          docType: "REAPPLY_SUPPORT",
          fileName: file.name,
          contentType: file.type || undefined,
          dataBase64: await fileToBase64(file),
        });
        documentId = up.id;
      }
      return customersApi.allowReapplication(customerId, { reason: reason.trim(), documentId });
    },
    onSuccess: () => {
      toast.success("Re-application allowed");
      void qc.invalidateQueries({ queryKey: ["reapply-allowance", customerId] });
      void qc.invalidateQueries({ queryKey: ["customer-documents", customerId] });
    },
  });

  const s = statusQ.data;
  if (!s || !s.disqualified || s.allowance) return null;

  const ready = reason.trim() !== "" && (file != null || docId !== "");
  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 font-serif text-base font-semibold text-navy">
        <RotateCcw size={16} /> Allow re-application
      </div>
      <p className="mb-2 text-xs text-muted">
        This customer cannot re-apply because of their repayment history. Allowing it once sends their next
        application to the normal credit queue; it is not pre-approved.
      </p>
      <textarea
        aria-label="Reason"
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder="Reason (required, kept on the audit trail)"
        rows={3}
        className="mb-2 w-full rounded border border-line px-2 py-1.5 text-sm"
      />
      <Select
        label="Supporting document"
        value={docId}
        disabled={file != null}
        onChange={(e) => setDocId(e.target.value)}
        helperText={docsQ.isLoading ? "Loading documents…" : docs.length === 0 ? "No documents on file — upload one below." : undefined}
      >
        <option value="">Select an existing document…</option>
        {docs.map((d) => (
          <option key={d.id} value={d.id}>
            {d.label}
          </option>
        ))}
      </Select>
      {latestApplicationId != null && (
        <label className="mb-2 block text-xs text-muted">
          Or upload a new one
          <input
            type="file"
            className="mt-1 block text-xs"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
        </label>
      )}
      {grant.error && <p className="mb-2 text-xs text-error-700">{formatApiError(grant.error, "Could not allow re-application.")}</p>}
      <button
        type="button"
        onClick={() => grant.mutate()}
        disabled={!ready || grant.isPending}
        className="btn btn-sm btn-navy disabled:opacity-50"
      >
        {grant.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Allow re-application
      </button>
    </div>
  );
}
