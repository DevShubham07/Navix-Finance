"use client";

import * as React from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice } from "@/components/staff/live-pipeline";
import { LeadCsvImport } from "@/components/staff/lead-csv-import";
import { hasPermission } from "@/lib/auth/rbac";
import { leadsApi } from "@/lib/api/applications";
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
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
