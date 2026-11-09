"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw } from "lucide-react";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import type { ExportColumn } from "@/lib/export/exporters";
import { staffApi, paiseToINR, type RejectionView } from "@/lib/api/applications";
import { CreditBadge } from "@/components/staff/credit-badge";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { type FilterColumn } from "@/components/staff/column-filter";
import { istDay } from "@/lib/staff/column-filter-match";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useClientRegister } from "@/lib/staff/use-client-register";
import { Th, thBag, describeRegister } from "@/lib/staff/register-th";

const REASON_LABEL: Record<string, string> = {
  SELF_EMPLOYED: "Self-employed",
  PAST_DELINQUENCY: "Past delinquency",
  LOW_BUREAU_SCORE: "Low credit score",
  /** V75: the Aadhaar number entered disagrees with the one on the PAN record / DigiLocker. */
  FRAUD_REJECTED: "Fraud rejected",
  /** The Aadhaar number is already on another customer's submitted application; the detail names it. */
  AADHAAR_DUPLICATE: "Duplicate Aadhaar",
  MANUAL: "Rejected by credit",
};

/** The IST business day, not a UTC slice: `.slice(0, 10)` on a timestamp shows the previous day for
 *  anything rejected after 5:30am UTC — and it has to agree with what the date filter compares. */
const day = (iso: string | null) => istDay(iso) ?? "";

/** Sortable + filterable columns. Identity (PAN, account, IFSC) is deliberately not among them:
 *  a dropdown of account numbers is not a feature, and nobody narrows a register by one. */
const SORT_KEYS = [
  "borrowerName", "amountRequestedPaise", "creditScore", "reasonCode", "auto", "blockedUntil", "createdAt",
] as const;

const COLUMN_LABEL: Record<string, string> = {
  borrowerName: "Borrower",
  amountRequestedPaise: "Amount",
  creditScore: "Credit",
  reasonCode: "Reason",
  auto: "Source",
  blockedUntil: "Blocked until",
  createdAt: "Rejected on",
};

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
 * 90-day cooling-off window, the past-delinquency engine rule, the sub-550 bureau-score auto-reject
 * (also a 90-day cooling-off), and manual credit rejections.
 * The borrower is never shown any of this; they always see the same neutral message.
 */
export default function AdminRejectionsPage() {
  const me = useStaffMe().data;
  const myRole = me?.role;
  const can = useCan();
  const q = useQuery({
    queryKey: ["admin-rejections"],
    queryFn: () => staffApi.rejections(),
  });

  const rows = React.useMemo(() => q.data ?? [], [q.data]);
  const now = Date.now();

  const rq = useRegisterQuery({
    registerId: "rejections",
    staffId: me?.id,
    sortKeys: SORT_KEYS,
    filterKeys: SORT_KEYS,
    defaults: { sort: { key: "createdAt", dir: "desc" } },
  });

  const filterColumns = React.useMemo<Array<FilterColumn<RejectionView>>>(
    () => [
      { key: "borrowerName", kind: "text", value: (r) => r.borrowerName ?? "" },
      // Rupees: the bound is typed next to a rupee column.
      { key: "amountRequestedPaise", kind: "num", value: (r) => (r.amountRequestedPaise != null ? paiseToINR(r.amountRequestedPaise) : "—"), num: (r) => (r.amountRequestedPaise != null ? r.amountRequestedPaise / 100 : null) },
      { key: "creditScore", kind: "num", value: (r) => (r.creditScore != null ? String(r.creditScore) : "—"), num: (r) => r.creditScore ?? null },
      { key: "reasonCode", value: (r) => REASON_LABEL[r.reasonCode] ?? r.reasonCode, raw: (r) => r.reasonCode },
      { key: "auto", value: (r) => (r.auto ? "Automatic" : "Manual"), raw: (r) => (r.auto ? "AUTO" : "MANUAL") },
      { key: "blockedUntil", kind: "date", value: (r) => day(r.blockedUntil) || "—", date: (r) => istDay(r.blockedUntil) },
      { key: "createdAt", kind: "date", value: (r) => day(r.createdAt) || "—", date: (r) => istDay(r.createdAt) },
    ],
    [],
  );

  const cr = useClientRegister(rows, filterColumns, rq);
  const { pageRows, total, pageCount } = cr;
  const th = thBag(rq, cr, filterColumns, COLUMN_LABEL);
  const page = rq.state.page;
  const pageSize = rq.state.size;

  if (myRole && !can("staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  return (
    <div>
      <PageHeader
        title="Rejections"
        subtitle={[
          "Every rejected application and the reason behind it. Admin only — never surfaced to the borrower.",
          describeRegister(th),
        ]
          .filter(Boolean)
          .join(" · ")}
      >
        <ExportMenu
          title="Rejections"
          subtitle="Rejection register"
          fileBase="dhanboost-rejections"
          columns={EXPORT_COLUMNS}
          rows={cr.rows}
          scope="all"
        />
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {/* The reason dropdown that used to sit here is gone: the Reason column's own filter does the
          same job, composes with the other columns, and survives a reload. */}
      <p className="mb-4 text-xs text-muted">
        {total} {total === 1 ? "rejection" : "rejections"}
        {rq.activeCount > 0 ? ` of ${rows.length}` : ""}
      </p>

      {q.isLoading ? (
        <Skeleton variant="table" rows={8} cols={12} className="rounded border border-line bg-white" />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyState title="No rejections yet." className="rounded border border-line bg-white shadow-sm" />
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          {/* Same register frame as the other admin tabs: `staff-register-scroll` bounds the table to
              the viewport and pins its header, so the pagination bar below stays on screen instead of
              sitting under every row of a long register. */}
          <div className="staff-table-scroll staff-register-scroll" style={{ "--register-offset": "20rem" } as React.CSSProperties}>
            <table className="staff-data-table">
              <caption className="sr-only">Rejections</caption>
              <thead>
                <tr>
                  <th>S.No.</th>
                  <Th th={th} col="borrowerName" />
                  <th>PAN</th>
                  <th>Account</th>
                  <th>IFSC</th>
                  <Th th={th} col="amountRequestedPaise" className="num" />
                  <Th th={th} col="creditScore" />
                  <Th th={th} col="reasonCode" />
                  <th>Detail</th>
                  <Th th={th} col="auto" />
                  <Th th={th} col="blockedUntil" />
                  <Th th={th} col="createdAt" />
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
                      <td className="num text-ink">
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
                {total === 0 ? (
                  // In-table, not instead of the table: an empty result must still show the headers,
                  // or there is no way to clear the filter that emptied it.
                  <EmptyState title="No rejections match the column filters." inTable={12} />
                ) : null}
              </tbody>
            </table>
          </div>
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={rq.setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={rq.setSize}
          />
        </div>
      )}
    </div>
  );
}
