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
import { staffApi, paiseToINR, type RejectionView } from "@/lib/api/applications";
import { CreditBadge } from "@/components/staff/credit-badge";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";

const REASONS = [
  { value: "", label: "All reasons" },
  { value: "SELF_EMPLOYED", label: "Self-employed" },
  { value: "PAST_DELINQUENCY", label: "Past delinquency" },
  { value: "LOW_BUREAU_SCORE", label: "Low credit score" },
  { value: "MANUAL", label: "Rejected by credit" },
] as const;

const REASON_LABEL: Record<string, string> = {
  SELF_EMPLOYED: "Self-employed",
  PAST_DELINQUENCY: "Past delinquency",
  LOW_BUREAU_SCORE: "Low credit score",
  MANUAL: "Rejected by credit",
};

const day = (iso: string | null) => (iso ? iso.slice(0, 10) : "");

const EXPORT_COLUMNS: ExportColumn<RejectionView>[] = [
  { header: "ID", value: (r) => r.id },
  { header: "App ID", value: (r) => r.applicationId ?? "" },
  { header: "Customer ID", value: (r) => r.customerId },
  { header: "Name", value: (r) => r.borrowerName ?? "" },
  { header: "Mobile", value: (r) => r.mobile ?? "" },
  { header: "PAN", value: (r) => r.pan ?? "" },
  { header: "Account", value: (r) => r.salaryAccountNumber ?? "" },
  { header: "IFSC", value: (r) => r.salaryIfsc ?? "" },
  { header: "Amount", value: (r) => (r.amountRequestedPaise != null ? (r.amountRequestedPaise / 100).toFixed(2) : "") },
  { header: "Credit score", value: (r) => r.creditScore ?? "" },
  { header: "Credit rating", value: (r) => (r.starRating != null ? r.starRating.toFixed(1) : "") },
  { header: "Reason", value: (r) => REASON_LABEL[r.reasonCode] ?? r.reasonCode },
  { header: "Detail", value: (r) => r.reasonDetail ?? "" },
  { header: "Source", value: (r) => (r.auto ? "automatic" : "manual") },
  { header: "Blocked until", value: (r) => day(r.blockedUntil) },
  { header: "Rejected on", value: (r) => day(r.createdAt) },
];

/**
 * Admin · rejections — every rejection in one register: the self-employed auto-reject and its
 * 90-day cooling-off window, the past-delinquency engine rule, the sub-600 bureau-score auto-reject
 * (also a 90-day cooling-off), and manual credit rejections.
 * The borrower is never shown any of this; they always see the same neutral message.
 */
export default function AdminRejectionsPage() {
  const myRole = useStaffMe().data?.role;
  const [reason, setReason] = React.useState("");
  const q = useQuery({
    queryKey: ["admin-rejections", reason],
    queryFn: () => staffApi.rejections(reason || undefined),
  });

  const rows = q.data ?? [];
  const now = Date.now();
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(rows);

  if (myRole && !hasPermission(myRole, "staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

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
          <div className="staff-table-scroll">
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>S.No.</th>
                  <th>Borrower</th>
                  <th>PAN</th>
                  <th>Account</th>
                  <th>IFSC</th>
                  <th>Amount</th>
                  <th>Credit</th>
                  <th>Reason</th>
                  <th>Detail</th>
                  <th>Source</th>
                  <th>Blocked until</th>
                  <th>Rejected on</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {pageRows.map((r, i) => {
                  const blocked = r.blockedUntil != null && Date.parse(r.blockedUntil) > now;
                  return (
                    <tr key={r.id} className="hover:bg-grey-50">
                      <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                      <td>
                        <div className="font-semibold text-navy">{r.borrowerName ?? "Name unavailable"}</div>
                        <div className="text-xs text-muted">
                          #{r.customerId}
                          {r.mobile ? ` · ${r.mobile}` : ""}
                          {r.applicationId ? ` · app ${r.applicationId}` : ""}
                        </div>
                      </td>
                      <td className="font-mono text-muted">{r.pan ?? "—"}</td>
                      <td className="font-mono text-muted">{r.salaryAccountNumber ?? "—"}</td>
                      <td className="font-mono text-muted">{r.salaryIfsc ?? "—"}</td>
                      <td className="text-ink">
                        {r.amountRequestedPaise != null ? paiseToINR(r.amountRequestedPaise) : "—"}
                      </td>
                      <td>
                        {r.creditScore != null || r.starRating != null ? (
                          <CreditBadge starRating={r.starRating} creditScore={r.creditScore} compact />
                        ) : (
                          <span className="text-xs text-muted">—</span>
                        )}
                      </td>
                      <td>
                        <span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">
                          {REASON_LABEL[r.reasonCode] ?? r.reasonCode}
                        </span>
                      </td>
                      <td className="text-muted">{r.reasonDetail ?? "—"}</td>
                      <td className="text-muted">{r.auto ? "Automatic" : "Manual"}</td>
                      <td>
                        {r.blockedUntil ? (
                          <span className={blocked ? "font-semibold text-error-700" : "text-muted"}>
                            {day(r.blockedUntil)}
                            {blocked ? " (active)" : " (expired)"}
                          </span>
                        ) : (
                          <span className="text-muted">—</span>
                        )}
                      </td>
                      <td className="text-muted">{day(r.createdAt)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={setPageSize}
          />
        </div>
      )}
    </div>
  );
}
