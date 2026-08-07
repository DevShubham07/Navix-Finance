"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw } from "lucide-react";
import { Select } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import type { ExportColumn } from "@/lib/export/exporters";
import { hasPermission } from "@/lib/auth/rbac";
import { staffApi, type RejectionView } from "@/lib/api/applications";

const REASONS = [
  { value: "", label: "All reasons" },
  { value: "SELF_EMPLOYED", label: "Self-employed" },
  { value: "PAST_DELINQUENCY", label: "Past delinquency" },
  { value: "MANUAL", label: "Rejected by credit" },
] as const;

const REASON_LABEL: Record<string, string> = {
  SELF_EMPLOYED: "Self-employed",
  PAST_DELINQUENCY: "Past delinquency",
  MANUAL: "Rejected by credit",
};

const day = (iso: string | null) => (iso ? iso.slice(0, 10) : "");

const EXPORT_COLUMNS: ExportColumn<RejectionView>[] = [
  { header: "ID", value: (r) => r.id },
  { header: "App ID", value: (r) => r.applicationId ?? "" },
  { header: "Customer ID", value: (r) => r.customerId },
  { header: "Name", value: (r) => r.borrowerName ?? "" },
  { header: "Mobile", value: (r) => r.mobile ?? "" },
  { header: "Reason", value: (r) => REASON_LABEL[r.reasonCode] ?? r.reasonCode },
  { header: "Detail", value: (r) => r.reasonDetail ?? "" },
  { header: "Source", value: (r) => (r.auto ? "automatic" : "manual") },
  { header: "Blocked until", value: (r) => day(r.blockedUntil) },
  { header: "Rejected on", value: (r) => day(r.createdAt) },
];

/**
 * Admin · rejections — every rejection in one register: the self-employed auto-reject and its
 * 90-day cooling-off window, the past-delinquency engine rule, and manual credit rejections.
 * The borrower is never shown any of this; they always see the same neutral message.
 */
export default function AdminRejectionsPage() {
  const myRole = useStaffMe().data?.role;
  const [reason, setReason] = React.useState("");
  const q = useQuery({
    queryKey: ["admin-rejections", reason],
    queryFn: () => staffApi.rejections(reason || undefined),
  });

  if (myRole && !hasPermission(myRole, "staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  const rows = q.data ?? [];
  const now = Date.now();

  return (
    <div>
      <PageHeader
        title="Rejections"
        subtitle="Every rejected application and the reason behind it. Admin only — never surfaced to the borrower."
      >
        <ExportMenu
          title="Rejections"
          subtitle="Rejection register"
          fileBase="dhanboost-rejections"
          columns={EXPORT_COLUMNS}
          rows={rows}
        />
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <div className="mb-4 flex flex-wrap items-end gap-2">
        <Select
          aria-label="Reason"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          className="!mb-0"
          options={REASONS.map((r) => ({ value: r.value, label: r.label }))}
        />
        <span className="pb-2 text-xs text-muted">{rows.length} rejections</span>
      </div>

      {q.isLoading ? (
        <div className="h-40 animate-pulse rounded border border-line bg-white" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : rows.length === 0 ? (
        <p className="rounded border border-line bg-white px-5 py-8 text-center text-sm text-muted shadow-sm">
          No rejections yet.
        </p>
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b border-line bg-grey-50 text-left text-xs uppercase tracking-wide text-muted">
                <tr>
                  <th className="px-4 py-2.5">Borrower</th>
                  <th className="px-4 py-2.5">Reason</th>
                  <th className="px-4 py-2.5">Detail</th>
                  <th className="px-4 py-2.5">Source</th>
                  <th className="px-4 py-2.5">Blocked until</th>
                  <th className="px-4 py-2.5">Rejected on</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {rows.map((r) => {
                  const blocked = r.blockedUntil != null && Date.parse(r.blockedUntil) > now;
                  return (
                    <tr key={r.id} className="hover:bg-grey-50">
                      <td className="px-4 py-2.5">
                        <div className="font-semibold text-navy">{r.borrowerName ?? "Name unavailable"}</div>
                        <div className="text-xs text-muted">
                          #{r.customerId}
                          {r.mobile ? ` · ${r.mobile}` : ""}
                          {r.applicationId ? ` · app ${r.applicationId}` : ""}
                        </div>
                      </td>
                      <td className="px-4 py-2.5">
                        <span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">
                          {REASON_LABEL[r.reasonCode] ?? r.reasonCode}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 text-muted">{r.reasonDetail ?? "—"}</td>
                      <td className="px-4 py-2.5 text-muted">{r.auto ? "Automatic" : "Manual"}</td>
                      <td className="px-4 py-2.5">
                        {r.blockedUntil ? (
                          <span className={blocked ? "font-semibold text-error-700" : "text-muted"}>
                            {day(r.blockedUntil)}
                            {blocked ? " (active)" : " (expired)"}
                          </span>
                        ) : (
                          <span className="text-muted">—</span>
                        )}
                      </td>
                      <td className="px-4 py-2.5 text-muted">{day(r.createdAt)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
