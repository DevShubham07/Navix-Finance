"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw } from "lucide-react";
import { PageHeader, StatCard } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice } from "@/components/staff/live-pipeline";
import { hasPermission } from "@/lib/auth/rbac";
import { dsaApi, paiseToINR, type DsaCommissionStatus } from "@/lib/api/applications";

const STATUS_TONE: Record<DsaCommissionStatus, string> = {
  ACCRUED: "bg-amber-50 text-amber-900",
  PAYABLE: "bg-sky-50 text-sky-800",
  PAID: "bg-emerald-50 text-emerald-800",
  VOID: "bg-red-50 text-red-800",
};

/**
 * DSA portal · My earnings — summary tiles (leads, converted, accrued/payable/paid) plus the
 * commission ledger for this DSA's own leads. Own data only (server-scoped off the JWT).
 */
export default function DsaEarningsPage() {
  const myRole = useStaffMe().data?.role;

  const summary = useQuery({ queryKey: ["dsa-earnings"], queryFn: dsaApi.earnings });
  const commissions = useQuery({ queryKey: ["dsa-commissions"], queryFn: dsaApi.commissions });

  if (myRole && !hasPermission(myRole, "dsa:portal")) {
    return <NoAccessNotice message="DSA access required." />;
  }

  const rows = commissions.data ?? [];
  const s = summary.data;

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
        <p className="mt-3 text-sm text-red-700">{errMessage(summary.error ?? commissions.error)}</p>
      )}

      <div className="staff-table-scroll mt-6 rounded-lg border border-navy/10 bg-white">
        <table className="staff-data-table">
          <thead>
            <tr>
              <th>PAN</th>
              <th>Name</th>
              <th>Mobile</th>
              <th className="text-right">Net disbursed</th>
              <th className="text-right">Commission</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="py-8 text-center text-navy/40">
                  {commissions.isLoading ? "Loading…" : "No commissions yet — they accrue once a lead's loan disburses."}
                </td>
              </tr>
            )}
            {rows.map((c) => (
              <tr key={c.id}>
                <td className="font-mono text-xs">{c.pan ?? "—"}</td>
                <td className="staff-cell font-medium text-navy">{c.name ?? "—"}</td>
                <td className="font-mono text-xs">{c.mobile ?? "—"}</td>
                <td className="text-right">{paiseToINR(c.netDisbursedPaise)}</td>
                <td className="text-right font-semibold text-navy">{paiseToINR(c.amountPaise)}</td>
                <td>
                  <span className={`inline-block rounded px-2 py-0.5 text-[8px] font-semibold uppercase ${STATUS_TONE[c.status]}`}>
                    {c.status}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
