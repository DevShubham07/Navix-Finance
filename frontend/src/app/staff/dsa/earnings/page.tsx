"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw } from "lucide-react";
import { PageHeader, StatCard } from "@/components/staff/staff-ui";
import { useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { dsaApi, paiseToINR, type DsaCommissionStatus } from "@/lib/api/applications";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { type FilterColumn } from "@/components/staff/column-filter";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useClientRegister } from "@/lib/staff/use-client-register";
import { Th, thBag } from "@/lib/staff/register-th";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";

const STATUS_TONE: Record<DsaCommissionStatus, string> = {
  ACCRUED: "bg-amber-50 text-amber-900",
  PAYABLE: "bg-sky-50 text-sky-800",
  PAID: "bg-emerald-50 text-emerald-800",
  VOID: "bg-red-50 text-red-800",
};

/** Sortable + filterable columns. PAN and mobile are identity, so they get no dropdown — a DSA
 *  looking for one lead reads the name. */
const SORT_KEYS = ["name", "netDisbursedPaise", "amountPaise", "status"] as const;

const COLUMN_LABEL: Record<string, string> = {
  name: "Name",
  netDisbursedPaise: "Net disbursed",
  amountPaise: "Commission",
  status: "Status",
};

/** ACCRUED → "Accrued": the badge shouts in uppercase, a filter checklist should not. */
const statusLabel = (v: DsaCommissionStatus) => v[0] + v.slice(1).toLowerCase();

/**
 * DSA portal · My earnings — summary tiles (leads, converted, accrued/payable/paid) plus the
 * commission ledger for this DSA's own leads. Own data only (server-scoped off the JWT).
 */
export default function DsaEarningsPage() {
  const me = useStaffMe().data;
  const myRole = me?.role;
  const can = useCan();

  const summary = useQuery({ queryKey: ["dsa-earnings"], queryFn: dsaApi.earnings });
  const commissions = useQuery({ queryKey: ["dsa-commissions"], queryFn: dsaApi.commissions });

  const rows = React.useMemo(() => commissions.data ?? [], [commissions.data]);
  const s = summary.data;

  const rq = useRegisterQuery({
    registerId: "dsa-earnings",
    staffId: me?.id,
    sortKeys: SORT_KEYS,
    filterKeys: SORT_KEYS,
    defaults: { sort: { key: "amountPaise", dir: "desc" } },
  });

  const filterColumns = React.useMemo<Array<FilterColumn<(typeof rows)[number]>>>(
    () => [
      { key: "name", kind: "text", value: (c) => c.name ?? "" },
      // Rupees on both money columns: the bound is typed beside a rupee figure.
      { key: "netDisbursedPaise", kind: "num", value: (c) => paiseToINR(c.netDisbursedPaise), num: (c) => c.netDisbursedPaise / 100 },
      { key: "amountPaise", kind: "num", value: (c) => paiseToINR(c.amountPaise), num: (c) => c.amountPaise / 100 },
      { key: "status", value: (c) => statusLabel(c.status), raw: (c) => c.status },
    ],
    [],
  );

  const cr = useClientRegister(rows, filterColumns, rq);
  const { pageRows, total, pageCount } = cr;
  const th = thBag(rq, cr, filterColumns, COLUMN_LABEL);
  const page = rq.state.page;
  const pageSize = rq.state.size;

  if (myRole && !can("dsa:portal")) {
    return <NoAccessNotice message="DSA access required." />;
  }

  return (
    <div>
      <PageHeader title="My earnings" subtitle="3.5% of the net disbursed amount on your lead's first loan, payable once fully repaid.">
        <button
          type="button"
          onClick={() => {
            summary.refetch();
            commissions.refetch();
          }}
          className="btn btn-sm btn-outline"
          disabled={summary.isFetching || commissions.isFetching}
        >
          {summary.isFetching || commissions.isFetching ? (
            <Loader2 size={14} className="animate-spin" />
          ) : (
            <RefreshCw size={14} />
          )}
          Refresh
        </button>
      </PageHeader>

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
        <StatCard label="Leads added" value={s ? s.leadsAdded : "—"} />
        <StatCard label="Converted" value={s ? s.leadsConverted : "—"} hint="applied through disbursed" />
        <StatCard label="Accrued" value={s ? paiseToINR(s.accruedPaise) : "—"} hint="loan disbursed, not yet repaid" accent="gold" />
        <StatCard label="Payable" value={s ? paiseToINR(s.payablePaise) : "—"} hint="loan fully repaid" accent="success" />
        <StatCard label="Paid" value={s ? paiseToINR(s.paidPaise) : "—"} accent="success" />
      </div>

      {(summary.isError || commissions.isError) && (
        <ErrorState
          error={summary.error ?? commissions.error}
          onRetry={() => {
            void summary.refetch();
            void commissions.refetch();
          }}
          className="mt-3 py-4"
        />
      )}

      <div className="staff-table-scroll mt-6 rounded-lg border border-navy/10 bg-white">
        <table className="staff-data-table">
          <thead>
            <tr>
              <th>S.No.</th>
              <th>PAN</th>
              <Th th={th} col="name" />
              <th>Mobile</th>
              <Th th={th} col="netDisbursedPaise" className="num text-right" />
              <Th th={th} col="amountPaise" className="num text-right" />
              <Th th={th} col="status" />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 &&
              (commissions.isLoading ? (
                <tr>
                  <td colSpan={7}>
                    <Skeleton variant="table" rows={8} cols={7} />
                  </td>
                </tr>
              ) : (
                <EmptyState inTable={7} title="No commissions yet — they accrue once a lead's loan disburses." />
              ))}
            {rows.length > 0 && total === 0 ? (
              <EmptyState inTable={7} title="No commissions match the column filters." />
            ) : null}
            {pageRows.map((c, i) => (
              <tr key={c.id}>
                <td className="text-navy/60">{(page - 1) * pageSize + i + 1}</td>
                <td className="font-mono text-xs">{c.pan ?? "—"}</td>
                <td className="staff-cell font-medium text-navy">{c.name ?? "—"}</td>
                <td className="font-mono text-xs">{c.mobile ?? "—"}</td>
                <td className="num text-right">{paiseToINR(c.netDisbursedPaise)}</td>
                <td className="num text-right font-semibold text-navy">{paiseToINR(c.amountPaise)}</td>
                <td>
                  <span className={`inline-block rounded px-2 py-0.5 text-[8px] font-semibold uppercase ${STATUS_TONE[c.status]}`}>
                    {c.status}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <PaginationBar
          page={page}
          pageCount={pageCount}
          setPage={rq.setPage}
          total={total}
          pageSize={pageSize}
          setPageSize={rq.setSize}
        />
      </div>
    </div>
  );
}
