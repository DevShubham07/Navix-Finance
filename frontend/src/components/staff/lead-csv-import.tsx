"use client";

import * as React from "react";
import Link from "next/link";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Loader2, Upload } from "lucide-react";
import { errMessage } from "@/components/staff/live-pipeline";
import { leadsApi, storageApi, type ImportJobView } from "@/lib/api/applications";

/** Structural guard only. 200k rows is ~12 MB of CSV or ~15 MB of xlsx; 25 MB leaves headroom. */
const MAX_FILE_BYTES = 25_000_000;
const POLL_MS = 2000;

const EXPECTED_HEADER = "name, contact number, pan card, pincode, emailid";

/**
 * "Import leads" — upload a .csv/.xlsx list of any size.
 *
 * <p>The file goes browser -> S3 directly on a presigned PUT (the same path borrower payslips and
 * expense receipts already take) and only its key is POSTed here. That is what makes a 1-2 lakh row
 * list possible: the old widget parsed in the browser and sent every row as one JSON array, which
 * cost ~200-300 MB of tab memory and exceeded the platform's request-body cap somewhere around
 * 15-20k rows.
 *
 * <p>Because nothing is parsed client-side there is no up-front duplicate preview any more. `merge`
 * is chosen before the upload instead, and the job reports what it did — including how many rows
 * were skipped as duplicates or as existing customers.
 */
export function LeadCsvImport({ onImported }: { onImported: () => void }) {
  const inputRef = React.useRef<HTMLInputElement>(null);
  const [merge, setMerge] = React.useState(false);
  const [jobId, setJobId] = React.useState<number | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [uploading, setUploading] = React.useState(false);
  const notifiedFor = React.useRef<number | null>(null);

  const job = useQuery({
    queryKey: ["lead-import-job", jobId],
    queryFn: () => leadsApi.importJob(jobId as number),
    enabled: jobId != null,
    // Stop hitting the API once the job can no longer change.
    refetchInterval: (query) => {
      const data = query.state.data as ImportJobView | undefined;
      return data && (data.status === "SUCCEEDED" || data.status === "FAILED") ? false : POLL_MS;
    },
  });

  // Refresh the caller's lead lists once, when the import actually finishes.
  React.useEffect(() => {
    const current = job.data;
    if (!current || current.status !== "SUCCEEDED") return;
    if (notifiedFor.current === current.id) return;
    notifiedFor.current = current.id;
    onImported();
  }, [job.data, onImported]);

  const upload = useMutation({
    mutationFn: async (file: File) => {
      const contentType =
        file.type ||
        (file.name.toLowerCase().endsWith(".csv")
          ? "text/csv"
          : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
      const { key, url } = await storageApi.presignUpload({
        category: "LEAD_IMPORT",
        filename: file.name,
        contentType,
      });
      await storageApi.putToPresignedUrl(url, file);
      return leadsApi.importFile({ s3Key: key, fileName: file.name, merge });
    },
    onSuccess: (started) => {
      notifiedFor.current = null;
      setJobId(started.id);
    },
  });

  async function handleFile(file: File) {
    setError(null);
    const name = file.name.toLowerCase();
    if (!name.endsWith(".csv") && !name.endsWith(".xlsx") && !name.endsWith(".xlsm")) {
      setError("Upload a .csv or .xlsx file.");
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      setError(`That file is ${(file.size / 1_000_000).toFixed(1)} MB — the limit is 25 MB.`);
      return;
    }
    setUploading(true);
    try {
      await upload.mutateAsync(file);
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setUploading(false);
    }
  }

  function onFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ""; // allow re-selecting the same file after a failed attempt
    if (file) void handleFile(file);
  }

  const current = job.data;
  const running = current?.status === "QUEUED" || current?.status === "RUNNING";
  const busy = uploading || running;

  return (
    <div className="w-full">
      <div className="flex flex-wrap items-center gap-3">
        <button
          type="button"
          className="btn btn-sm btn-outline disabled:opacity-50"
          disabled={busy}
          onClick={() => inputRef.current?.click()}
        >
          {busy ? <Loader2 size={14} className="animate-spin" /> : <Upload size={14} />}
          {uploading ? "Uploading…" : running ? "Importing…" : "Import leads"}
        </button>
        <input
          ref={inputRef}
          type="file"
          accept=".csv,.xlsx,.xlsm"
          onChange={onFileChange}
          className="hidden"
        />
        <label className="flex items-center gap-2 text-xs text-navy/70">
          <input
            type="checkbox"
            checked={merge}
            disabled={busy}
            onChange={(e) => setMerge(e.target.checked)}
          />
          Fill blank email / pincode / PAN on leads that already exist
        </label>
      </div>

      <p className="mt-1 text-xs text-navy/50">
        .csv or .xlsx, up to 25 MB. Expected columns:{" "}
        <span className="font-mono">{EXPECTED_HEADER}</span>. Re-uploading the same file is safe —
        rows that already exist are skipped.
      </p>

      {error && <p className="mt-2 text-sm text-error-700">{error}</p>}

      {current && <ImportProgress job={current} />}
    </div>
  );
}

function ImportProgress({ job }: { job: ImportJobView }) {
  const done = job.status === "SUCCEEDED";
  const failed = job.status === "FAILED";
  // totalRows only lands once the file has been read to the end, so until then the bar is
  // indeterminate rather than lying about a denominator it does not have.
  const pct =
    job.totalRows && job.totalRows > 0
      ? Math.min(100, Math.round((job.processedRows / job.totalRows) * 100))
      : null;

  return (
    <div
      className={`mt-3 rounded border p-3 text-sm ${
        failed
          ? "border-error-200 bg-error-50 text-error-900"
          : done
            ? "border-emerald-200 bg-emerald-50 text-emerald-900"
            : "border-line bg-grey-50 text-navy"
      }`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-semibold">
          {failed ? "Import failed" : done ? "Import finished" : "Importing"} · {job.fileName}
        </span>
        <span className="font-mono text-xs">
          {job.processedRows.toLocaleString("en-IN")}
          {job.totalRows ? ` / ${job.totalRows.toLocaleString("en-IN")}` : ""} rows
        </span>
      </div>

      {!done && !failed && (
        <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-navy/10">
          <div
            className={`h-full bg-navy transition-all ${pct == null ? "w-1/3 animate-pulse" : ""}`}
            style={pct == null ? undefined : { width: `${pct}%` }}
          />
        </div>
      )}

      {(done || failed) && (
        <p className="mt-2">
          Imported <strong>{job.insertedCount.toLocaleString("en-IN")}</strong> · merged{" "}
          {job.mergedCount.toLocaleString("en-IN")} · skipped{" "}
          {job.skippedDuplicates.toLocaleString("en-IN")} duplicates ·{" "}
          {job.skippedCustomers.toLocaleString("en-IN")} already customers
          {job.issueCount > 0 ? ` · ${job.issueCount.toLocaleString("en-IN")} rows had problems` : ""}.
        </p>
      )}

      {failed && job.errorMessage && <p className="mt-1">{job.errorMessage}</p>}

      {job.issues.length > 0 && (
        <details className="mt-2">
          <summary className="cursor-pointer text-xs font-semibold">
            First {job.issues.length} problem row{job.issues.length === 1 ? "" : "s"}
          </summary>
          <ul className="mt-1 max-h-48 list-disc space-y-0.5 overflow-y-auto pl-5 text-xs">
            {job.issues.map((issue, i) => (
              <li key={i}>
                Row {issue.row}: {issue.field} — {issue.message}
              </li>
            ))}
          </ul>
        </details>
      )}

      {done && job.insertedCount > 0 && (
        <p className="mt-2 text-xs">
          Uploaded leads are unattributed — see them under{" "}
          <Link href="/staff/admin/leads" className="font-semibold underline">
            Admin › Leads
          </Link>{" "}
          (Source: OTHER).
        </p>
      )}
    </div>
  );
}
