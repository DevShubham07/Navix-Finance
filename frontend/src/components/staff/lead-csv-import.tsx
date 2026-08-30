"use client";

import * as React from "react";
import Link from "next/link";
import { useMutation } from "@tanstack/react-query";
import { Loader2, Upload } from "lucide-react";
import { Dialog, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui";
import { errMessage } from "@/components/staff/live-pipeline";
import {
  leadsApi,
  type ImportIssue,
  type ImportPreview,
  type ImportResult,
  type ImportRow,
} from "@/lib/api/applications";
import { parseCsv, mapLeadCsv, EXPECTED_HEADER } from "@/lib/csv/parse-csv";

const MAX_FILE_BYTES = 1_000_000; // 1 MB — structural limit, checked before the file is even read.

type Stage =
  | { kind: "idle" }
  | { kind: "format"; issues: ImportIssue[] }
  | { kind: "confirm"; fileName: string; rows: ImportRow[] }
  | { kind: "duplicates"; fileName: string; preview: ImportPreview }
  | { kind: "result"; result: ImportResult };

/**
 * "Import CSV" on the DSA-program Leads tab's filter bar. Parses + pre-validates the file in the
 * browser (an obviously wrong file never leaves it), then previews against the backend — which
 * re-validates and classifies every row (new / duplicate-of-an-existing-lead / duplicate-within-
 * the-file / already-a-customer) — before the admin confirms. Imported rows are unattributed
 * (`owner_dsa_id = null`, `source = "OTHER"`), so they never show up in this DSA register.
 */
export function LeadCsvImport({ onImported }: { onImported: () => void }) {
  const inputRef = React.useRef<HTMLInputElement>(null);
  const [stage, setStage] = React.useState<Stage>({ kind: "idle" });
  const [pendingFileName, setPendingFileName] = React.useState<string>("");
  const [pendingRows, setPendingRows] = React.useState<ImportRow[]>([]);

  const previewMutation = useMutation({
    mutationFn: (body: { fileName: string; rows: ImportRow[] }) =>
      leadsApi.importPreview({ fileName: body.fileName, rows: body.rows, merge: false }),
  });

  const commitMutation = useMutation({
    mutationFn: (body: { fileName: string; rows: ImportRow[]; merge: boolean }) =>
      leadsApi.importCommit(body),
    onSuccess: (result) => {
      setStage({ kind: "result", result });
      onImported();
    },
  });

  async function handleFile(file: File) {
    if (file.size > MAX_FILE_BYTES) {
      setStage({ kind: "format", issues: [{ row: 0, field: "file", message: "The file is larger than 1 MB." }] });
      return;
    }

    const text = await file.text();
    const { rows, issues } = mapLeadCsv(parseCsv(text));
    if (issues.length > 0) {
      setStage({ kind: "format", issues });
      return;
    }

    setPendingFileName(file.name);
    setPendingRows(rows);
    try {
      const preview = await previewMutation.mutateAsync({ fileName: file.name, rows });
      if (preview.issues.length > 0) {
        setStage({ kind: "format", issues: preview.issues });
        return;
      }
      if (preview.duplicates.length > 0 || preview.inFileDuplicates.length > 0 || preview.existingCustomers.length > 0) {
        setStage({ kind: "duplicates", fileName: file.name, preview });
      } else {
        setStage({ kind: "confirm", fileName: file.name, rows });
      }
    } catch (e) {
      setStage({ kind: "format", issues: [{ row: 0, field: "file", message: errMessage(e) }] });
    }
  }

  function onFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ""; // allow re-selecting the same file after a failed attempt
    if (file) void handleFile(file);
  }

  const closeToIdle = () => setStage({ kind: "idle" });

  return (
    <>
      <button type="button" className="btn btn-sm btn-outline" onClick={() => inputRef.current?.click()}>
        <Upload size={14} /> Import CSV
      </button>
      <input ref={inputRef} type="file" accept=".csv,text/csv" onChange={onFileChange} className="hidden" />

      {stage.kind === "format" && (
        <Dialog open onClose={closeToIdle} aria-labelledby="csv-import-format-title">
          <DialogHeader>
            <DialogTitle id="csv-import-format-title">This file isn&apos;t in the expected format</DialogTitle>
            <p className="text-sm text-navy/60">
              Expected header: <span className="font-mono text-xs">{EXPECTED_HEADER}</span>
            </p>
          </DialogHeader>
          <ul className="max-h-64 list-disc space-y-1 overflow-y-auto pl-5 text-sm text-navy/80">
            {stage.issues.map((iss, idx) => (
              <li key={idx}>
                Row {iss.row}: {iss.field} — {iss.message}
              </li>
            ))}
          </ul>
          <DialogFooter>
            <button type="button" className="btn btn-outline" onClick={closeToIdle}>
              Close
            </button>
          </DialogFooter>
        </Dialog>
      )}

      {stage.kind === "confirm" && (
        <Dialog open onClose={closeToIdle} aria-labelledby="csv-import-confirm-title">
          <DialogHeader>
            <DialogTitle id="csv-import-confirm-title">
              Import {stage.rows.length} leads from {stage.fileName}?
            </DialogTitle>
          </DialogHeader>
          {commitMutation.isError && (
            <p className="text-sm text-error-700">{errMessage(commitMutation.error)}</p>
          )}
          <DialogFooter>
            <button type="button" className="btn btn-outline" onClick={closeToIdle} disabled={commitMutation.isPending}>
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-gold disabled:opacity-50"
              disabled={commitMutation.isPending}
              onClick={() => commitMutation.mutate({ fileName: stage.fileName, rows: stage.rows, merge: false })}
            >
              {commitMutation.isPending ? <Loader2 size={14} className="animate-spin" /> : null}
              Import
            </button>
          </DialogFooter>
        </Dialog>
      )}

      {stage.kind === "duplicates" && (
        <DuplicatesDialog
          fileName={stage.fileName}
          preview={stage.preview}
          pending={commitMutation.isPending}
          error={commitMutation.isError ? commitMutation.error : null}
          onCancel={closeToIdle}
          onImportNew={() => commitMutation.mutate({ fileName: pendingFileName, rows: pendingRows, merge: false })}
          onMerge={() => commitMutation.mutate({ fileName: pendingFileName, rows: pendingRows, merge: true })}
        />
      )}

      {stage.kind === "result" && (
        <div className="mt-2 w-full rounded border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900">
          Imported {stage.result.inserted} · merged {stage.result.merged} · skipped{" "}
          {stage.result.skippedDuplicates} duplicates · {stage.result.skippedCustomers} already customers.
          <br />
          Uploaded leads are unattributed — see them under{" "}
          <Link href="/staff/admin/leads" className="font-semibold underline">
            Admin › Leads
          </Link>{" "}
          (Source: OTHER).
        </div>
      )}
    </>
  );
}

function DuplicatesDialog({
  fileName,
  preview,
  pending,
  error,
  onCancel,
  onImportNew,
  onMerge,
}: {
  fileName: string;
  preview: ImportPreview;
  pending: boolean;
  error: unknown;
  onCancel: () => void;
  onImportNew: () => void;
  onMerge: () => void;
}) {
  const totalKnown = preview.duplicates.length + preview.inFileDuplicates.length + preview.existingCustomers.length;
  const hasFillable = preview.duplicates.some((d) => d.fillableFields.length > 0);

  return (
    <Dialog open onClose={onCancel} aria-labelledby="csv-import-duplicates-title" className="max-w-3xl">
      <DialogHeader>
        <DialogTitle id="csv-import-duplicates-title">
          {totalKnown} of {preview.totalRows} leads already exist
        </DialogTitle>
        <p className="text-sm text-navy/60">{fileName}</p>
      </DialogHeader>

      {preview.duplicates.length > 0 && (
        <div className="mb-4">
          <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-navy/50">
            Matches an existing lead
          </p>
          <div className="staff-table-scroll max-h-56 rounded border border-line">
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>Row</th>
                  <th>Name</th>
                  <th>Mobile</th>
                  <th>PAN</th>
                  <th>Matched on</th>
                  <th>Existing lead</th>
                  <th>Will fill</th>
                </tr>
              </thead>
              <tbody>
                {preview.duplicates.map((d) => (
                  <tr key={d.row}>
                    <td>{d.row}</td>
                    <td>{d.name}</td>
                    <td className="font-mono text-xs">{d.mobile}</td>
                    <td className="font-mono text-xs">{d.pan ?? "—"}</td>
                    <td>{d.matchedOn.replace(/_/g, " ")}</td>
                    <td>
                      #{d.existingLeadId} {d.existingName} ({d.existingMobile}
                      {d.existingSource ? `, ${d.existingSource}` : ""})
                    </td>
                    <td>{d.fillableFields.length ? d.fillableFields.join(", ") : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {preview.inFileDuplicates.length > 0 && (
        <div className="mb-4">
          <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-navy/50">
            Duplicate rows within this file
          </p>
          <div className="staff-table-scroll max-h-40 rounded border border-line">
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>Row</th>
                  <th>Duplicate of row</th>
                  <th>Matched on</th>
                </tr>
              </thead>
              <tbody>
                {preview.inFileDuplicates.map((d, i) => (
                  <tr key={i}>
                    <td>{d.row}</td>
                    <td>{d.duplicateOfRow}</td>
                    <td>{d.matchedOn.replace(/_/g, " ")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {preview.existingCustomers.length > 0 && (
        <div className="mb-4">
          <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-navy/50">
            Already customers (skipped)
          </p>
          <div className="staff-table-scroll max-h-40 rounded border border-line">
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>Row</th>
                  <th>Name</th>
                  <th>Mobile</th>
                  <th>PAN</th>
                </tr>
              </thead>
              <tbody>
                {preview.existingCustomers.map((c) => (
                  <tr key={c.row}>
                    <td>{c.row}</td>
                    <td>{c.name}</td>
                    <td className="font-mono text-xs">{c.mobile}</td>
                    <td className="font-mono text-xs">{c.panMasked ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {error ? <p className="text-sm text-error-700">{errMessage(error)}</p> : null}
      {!hasFillable && preview.duplicates.length > 0 && (
        <p className="text-xs text-navy/50">Nothing to fill on the existing leads.</p>
      )}

      <DialogFooter>
        <button type="button" className="btn btn-outline" onClick={onCancel} disabled={pending}>
          Cancel
        </button>
        <button type="button" className="btn btn-outline disabled:opacity-50" disabled={pending} onClick={onImportNew}>
          {pending ? <Loader2 size={14} className="animate-spin" /> : null}
          Import {preview.newRows} new, skip duplicates
        </button>
        <button
          type="button"
          className="btn btn-gold disabled:opacity-50"
          disabled={pending || !hasFillable}
          onClick={onMerge}
        >
          {pending ? <Loader2 size={14} className="animate-spin" /> : null}
          Merge {preview.duplicates.length} duplicates and import
        </button>
      </DialogFooter>
    </Dialog>
  );
}
