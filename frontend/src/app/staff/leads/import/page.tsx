"use client";

import * as React from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice } from "@/components/staff/live-pipeline";
import { LeadCsvImport } from "@/components/staff/lead-csv-import";
import { hasPermission } from "@/lib/auth/rbac";
import { leadsApi, storageApi, type ImportJobView } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

/**
 * The shared upload surface.
 *
 * <p>Every staff role may bulk-upload a lead list, so the widget needs a page every role can reach:
 * `/staff/leads` is gated on `leads:manage` (Telecaller + Admin) and a DSA only ever sees
 * `/staff/dsa/*`. This page carries the upload and the caller's own import history and nothing else
 * — it deliberately does NOT show the lead register, which stays behind `leads:manage`.
 */
export default function LeadImportPage() {
  const myRole = useStaffMe().data?.role;
  const qc = useQueryClient();

  const jobs = useQuery({ queryKey: ["lead-import-jobs"], queryFn: leadsApi.importJobs });

  if (myRole && !hasPermission(myRole, "leads:import")) {
    return <NoAccessNotice message="You do not have access to lead imports." />;
  }

  const rows = jobs.data ?? [];

  return (
    <div>
      <PageHeader
        title="Import leads"
        subtitle="Upload a .csv or .xlsx list. Large files are processed in the background — you can leave this page."
      />

      <div className="mb-6 rounded border border-line bg-white p-4 shadow-sm">
        <LeadCsvImport
          onImported={() => {
            qc.invalidateQueries({ queryKey: ["lead-import-jobs"] });
            qc.invalidateQueries({ queryKey: ["admin-leads"] });
            qc.invalidateQueries({ queryKey: ["lead-stats"] });
          }}
        />
      </div>

      <h2 className="mb-2 text-base">Your recent imports</h2>
      <div className="staff-table-scroll rounded border border-line bg-white shadow-sm">
        {jobs.isLoading ? (
          <div className="h-24 animate-pulse bg-white" />
        ) : jobs.error ? (
          <p className="px-5 py-4 text-sm text-error-700">{errMessage(jobs.error)}</p>
        ) : rows.length === 0 ? (
          <p className="px-5 py-8 text-center text-sm text-muted">
            You haven&apos;t imported any lead lists yet.
          </p>
        ) : (
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>Started</th>
                <th>File</th>
                <th>Status</th>
                <th>Rows</th>
                <th>Imported</th>
                <th>Merged</th>
                <th>Duplicates</th>
                <th>Customers</th>
                <th>Problems</th>
                <th>Original</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((job) => (
                <tr key={job.id}>
                  <td>{formatDateTime(job.createdAt)}</td>
                  <td>{job.fileName}</td>
                  <td className={job.status === "FAILED" ? "font-semibold text-error-700" : undefined}>
                    {job.status}
                  </td>
                  <td className="font-mono">
                    {job.processedRows.toLocaleString("en-IN")}
                    {job.totalRows ? ` / ${job.totalRows.toLocaleString("en-IN")}` : ""}
                  </td>
                  <td className="font-mono">{job.insertedCount.toLocaleString("en-IN")}</td>
                  <td className="font-mono">{job.mergedCount.toLocaleString("en-IN")}</td>
                  <td className="font-mono">{job.skippedDuplicates.toLocaleString("en-IN")}</td>
                  <td className="font-mono">{job.skippedCustomers.toLocaleString("en-IN")}</td>
                  <td className="font-mono">{job.issueCount.toLocaleString("en-IN")}</td>
                  <td>
                    <OriginalFileLink job={job} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

/**
 * Download the list exactly as it was uploaded.
 *
 * <p>The file is PUT to S3 before the backend is told anything about it, and nothing ever deletes it
 * — not a parse failure, not a rejected format, not an import that died half way. So the row always
 * knows where the original is, and this is how someone gets it back to see what was wrong with it.
 * The key is withheld from roles without `customer:view` (a DSA sees their own counts but not the
 * contact data), in which case there is nothing to link to.
 */
function OriginalFileLink({ job }: { job: ImportJobView }) {
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  if (!job.s3Key) return <span className="text-xs text-muted">—</span>;

  async function open() {
    setBusy(true);
    setError(null);
    try {
      // Presigned GET, minted on demand: the URL is short-lived, so it is fetched at click time
      // rather than rendered into the table for every row.
      const url = await storageApi.presignDownload(job.s3Key as string);
      window.open(url, "_blank", "noopener,noreferrer");
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button type="button" className="text-xs underline disabled:opacity-50" disabled={busy} onClick={open}>
        {busy ? "Opening…" : "Original"}
      </button>
      {error && <p className="text-xs text-error-700">{error}</p>}
    </>
  );
}
