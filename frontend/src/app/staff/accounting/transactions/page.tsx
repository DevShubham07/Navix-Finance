"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, Search, ArrowDownLeft, ArrowUpRight, ArrowLeft } from "lucide-react";
import { Input } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { PermissionGate, NoAccessNotice, ROLE_LABEL, useStaffMe, errMessage } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { staffApi, paiseToINR, type TransactionDirection, type TransactionView } from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

const TABS: { key: "ALL" | TransactionDirection; label: string }[] = [
  { key: "ALL", label: "All" },
  { key: "INCOMING", label: "Incoming" },
  { key: "OUTGOING", label: "Outgoing" },
];

/**
 * Accountant transactions ledger: every money movement company-wide — OUTGOING disbursals and
 * INCOMING repayments — with a borrower search and direction tabs. Read-only oversight.
 */
export default function TransactionsPage() {
  const role = useStaffMe().data?.role;
  const [tab, setTab] = React.useState<"ALL" | TransactionDirection>("ALL");
  const [search, setSearch] = React.useState("");
  const [debounced, setDebounced] = React.useState("");

  React.useEffect(() => {
    const t = setTimeout(() => setDebounced(search.trim()), 300);
    return () => clearTimeout(t);
  }, [search]);

  const direction = tab === "ALL" ? undefined : tab;
  const q = useQuery({
    queryKey: ["staff-transactions", debounced, direction],
    queryFn: () => staffApi.transactions(debounced || undefined, direction),
    refetchInterval: 10_000,
  });

  const rows = q.data ?? [];
  const totalIn = rows.filter((r) => r.direction === "INCOMING").reduce((s, r) => s + r.amountPaise, 0);
  const totalOut = rows.filter((r) => r.direction === "OUTGOING").reduce((s, r) => s + r.amountPaise, 0);

  return (
    <div>
      <PageHeader title="Transactions ledger" subtitle="All money movement — disbursals out and repayments in, company-wide.">
        <ExportMenu
          title="Transactions ledger"
          subtitle={tab === "ALL" ? "All movements" : tab === "INCOMING" ? "Incoming" : "Outgoing"}
          fileBase="navix-transactions"
          columns={[
            { header: "Date", value: (t: TransactionView) => (t.date ? formatDate(t.date) : "") },
            { header: "Borrower", value: (t) => t.borrowerName ?? "" },
            { header: "PAN", value: (t) => t.pan ?? "" },
            { header: "Type", value: (t) => (t.type === "REPAYMENT" ? "Repayment" : "Disbursal") },
            { header: "Direction", value: (t) => t.direction },
            { header: "Amount (₹)", value: (t) => (t.amountPaise / 100).toFixed(2) },
            { header: "Reference", value: (t) => t.txnRef ?? "" },
            { header: "Status", value: (t) => t.status ?? "" },
            { header: "Loan", value: (t) => (t.loanId != null ? `#${t.loanId}` : "") },
          ]}
          rows={rows}
        />
        {role && <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{ROLE_LABEL[role]}</span>}
      </PageHeader>

      <PermissionGate permission="loan:activate" fallback={<NoAccessNotice />}>
        <div className="mb-4">
          <Link href="/staff/accounting" className="inline-flex items-center gap-1 text-sm text-navy hover:underline">
            <ArrowLeft size={14} /> Back to accounting
          </Link>
        </div>

        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-1 rounded-full border border-line bg-white p-1">
            {TABS.map((t) => (
              <button
                key={t.key}
                onClick={() => setTab(t.key)}
                className={`rounded-full px-3 py-1 text-sm font-semibold transition ${tab === t.key ? "bg-navy text-white" : "text-muted hover:text-navy"}`}
              >
                {t.label}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-2">
            <Input
              aria-label="Search transactions by borrower"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search borrower / mobile / loan #"
              leftIcon={<Search size={15} />}
              className="!mb-0"
              inputClassName="w-64"
            />
            <button
              onClick={() => q.refetch()}
              className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
            >
              {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
            </button>
          </div>
        </div>

        <div className="mb-4 grid grid-cols-2 gap-4 sm:max-w-md">
          <div className="rounded border border-success-100 bg-white p-4 shadow-sm">
            <div className="flex items-center gap-1.5 text-xs text-muted"><ArrowDownLeft size={14} className="text-success-600" /> Incoming (shown)</div>
            <div className="mt-1 font-serif text-xl font-bold text-navy">{paiseToINR(totalIn)}</div>
          </div>
          <div className="rounded border border-line bg-white p-4 shadow-sm">
            <div className="flex items-center gap-1.5 text-xs text-muted"><ArrowUpRight size={14} className="text-navy" /> Outgoing (shown)</div>
            <div className="mt-1 font-serif text-xl font-bold text-navy">{paiseToINR(totalOut)}</div>
          </div>
        </div>

        <div className="rounded border border-line bg-white shadow-sm">
          {q.isLoading ? (
            <div className="h-40 animate-pulse rounded bg-grey-100" />
          ) : q.error ? (
            <p className="px-5 py-4 text-sm text-error-700">{errMessage(q.error)}</p>
          ) : rows.length === 0 ? (
            <p className="px-5 py-8 text-center text-sm text-muted">
              No transactions{debounced ? ` for “${debounced}”` : ""}.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
                    <th className="px-4 py-2.5 font-semibold">Date</th>
                    <th className="px-4 py-2.5 font-semibold">Borrower</th>
                    <th className="px-4 py-2.5 font-semibold">Type</th>
                    <th className="px-4 py-2.5 font-semibold">Amount</th>
                    <th className="px-4 py-2.5 font-semibold">Reference</th>
                    <th className="px-4 py-2.5 font-semibold">Status</th>
                    <th className="px-4 py-2.5 font-semibold">Loan</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <TxnRow key={r.id} t={r} />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </PermissionGate>
    </div>
  );
}

function TxnRow({ t }: { t: TransactionView }) {
  const incoming = t.direction === "INCOMING";
  return (
    <tr className="border-b border-line/60">
      <td className="px-4 py-2.5 text-muted">{t.date ? formatDate(t.date) : "—"}</td>
      <td className="px-4 py-2.5">
        <span className="font-medium text-ink">{t.borrowerName ?? "—"}</span>
        {t.pan && <span className="ml-1 font-mono text-xs text-muted">{t.pan}</span>}
      </td>
      <td className="px-4 py-2.5">
        <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold ${incoming ? "bg-success-50 text-success-700" : "bg-navy-tint text-navy"}`}>
          {incoming ? <ArrowDownLeft size={12} /> : <ArrowUpRight size={12} />}
          {t.type === "REPAYMENT" ? "Repayment" : "Disbursal"}
        </span>
      </td>
      <td className={`px-4 py-2.5 font-semibold ${incoming ? "text-success-700" : "text-ink"}`}>
        {incoming ? "+" : "−"}{paiseToINR(t.amountPaise)}
      </td>
      <td className="px-4 py-2.5 text-muted">{t.txnRef || "—"}</td>
      <td className="px-4 py-2.5 text-muted">{t.status ?? "—"}</td>
      <td className="px-4 py-2.5 text-muted">{t.loanId != null ? `#${t.loanId}` : "—"}</td>
    </tr>
  );
}
