"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Gift, Check, Clock } from "lucide-react";
import { EmptyState, ErrorState, Input, Skeleton, toast } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, useCan, NoAccessNotice, ROLE_LABEL } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { staffReferralApi, featureFlagsApi, paiseToINR, type ReferralPayout } from "@/lib/api/applications";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { type FilterColumn } from "@/components/staff/column-filter";
import { istDay } from "@/lib/staff/column-filter-match";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useClientRegister } from "@/lib/staff/use-client-register";
import { Th, thBag, describeRegister } from "@/lib/staff/register-th";

/** The IST business day for a nullable ISO timestamp. Not `toLocaleDateString`, which names the
 *  viewer's own day: the column and its date filter must agree on which day a payout was paid. */
function fmtDate(iso: string | null): string {
  return istDay(iso) ?? "—";
}

/** Filterable columns. Loan # and txn ref are identity — nobody narrows a register by one. `paidBy`
 *  and `paidAt` exist only on the Paid tab. */
const SORT_KEYS = ["beneficiaryName", "beneficiaryRole", "counterpartyName", "amountPaise", "paidBy", "paidAt"] as const;

const COLUMN_LABEL: Record<string, string> = {
  beneficiaryName: "Beneficiary",
  beneficiaryRole: "Role",
  counterpartyName: "Referral",
  amountPaise: "Amount",
  paidBy: "Paid by",
  paidAt: "Paid at",
};

const PAID_ONLY_KEYS = ["paidBy", "paidAt"];

function roleLabel(role: ReferralPayout["beneficiaryRole"]): string {
  return role === "REFERRER" ? "Referrer" : "New borrower";
}

/**
 * Disbursement Head · referral payouts. Settle the ₹-rewards a qualified referral created (one for the
 * referrer, one for the new borrower): enter the bank/UPI transaction id and mark each paid — which
 * credits the beneficiary (notification). The Paid tab doubles as the separate referral-expense view
 * (totals + CSV/PDF export). Live `/api/referral/payouts`. DISBURSEMENT_HEAD / ADMIN only.
 */
export default function ReferralPayoutsPage() {
  const me = useStaffMe().data;
  const role = me?.role;
  const can = useCan();
  const qc = useQueryClient();
  const [tab, setTab] = React.useState<"PENDING" | "PAID">("PENDING");
  const [txnRefs, setTxnRefs] = React.useState<Record<number, string>>({});

  const allowed = role ? can("referral:payout") : false;

  // Dev-controlled kill-switch: when the referral flag is off the whole feature (incl. these staff
  // endpoints) is disabled, so don't fire the payout/expense reads — show the disabled state instead.
  const flagsQ = useQuery({
    queryKey: ["feature-flags"],
    queryFn: () => featureFlagsApi.get(),
    staleTime: 60_000,
  });
  const referralOff = flagsQ.data?.referral === false;

  const payoutsQ = useQuery({
    queryKey: ["referral-payouts", tab],
    queryFn: () => staffReferralApi.payouts(tab),
    enabled: allowed && !referralOff,
  });
  const expensesQ = useQuery({
    queryKey: ["referral-expenses"],
    queryFn: staffReferralApi.expenses,
    enabled: allowed && !referralOff,
  });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["referral-payouts"] });
    qc.invalidateQueries({ queryKey: ["referral-expenses"] });
  };

  const pay = useMutation({
    mutationFn: ({ id, txnRef }: { id: number; txnRef: string }) => staffReferralApi.pay(id, txnRef),
    onSuccess: (_data, vars) => {
      setTxnRefs((m) => {
        const next = { ...m };
        delete next[vars.id];
        return next;
      });
      invalidate();
      toast.success("Payout marked paid");
    },
  });

  const rows = React.useMemo(() => payoutsQ.data ?? [], [payoutsQ.data]);
  const sum = expensesQ.data;

  const rq = useRegisterQuery({
    registerId: "referrals",
    staffId: me?.id,
    sortKeys: SORT_KEYS,
    filterKeys: SORT_KEYS,
    defaults: { sort: { key: "paidAt", dir: "desc" } },
  });

  // Every column is declared even though a tab shows only some: the rows are fetched per tab, so a
  // column the tab lacks simply has nothing to filter.
  const filterColumns = React.useMemo<Array<FilterColumn<ReferralPayout>>>(
    () => [
      { key: "beneficiaryName", kind: "text", value: (p) => p.beneficiaryName ?? `#${p.beneficiaryCustomerId}` },
      // The checklist reads "New borrower"; the filter travels as REFERRED.
      { key: "beneficiaryRole", value: (p) => roleLabel(p.beneficiaryRole), raw: (p) => p.beneficiaryRole },
      { key: "counterpartyName", kind: "text", value: (p) => p.counterpartyName ?? (p.counterpartyCustomerId ? `#${p.counterpartyCustomerId}` : "—") },
      // Rupees: the bound is typed next to a rupee column.
      { key: "amountPaise", kind: "num", value: (p) => paiseToINR(p.amountPaise), num: (p) => p.amountPaise / 100 },
      { key: "paidBy", value: (p) => p.paidBy ?? "—", raw: (p) => p.paidBy ?? "" },
      { key: "paidAt", kind: "date", value: (p) => fmtDate(p.paidAt), date: (p) => istDay(p.paidAt) },
    ],
    [],
  );

  const cr = useClientRegister(rows, filterColumns, rq);
  const { pageRows, total, pageCount } = cr;
  const th = thBag(rq, cr, filterColumns, COLUMN_LABEL);
  const page = rq.state.page;
  const pageSize = rq.state.size;

  if (role && !allowed) {
    return <NoAccessNotice message="Disbursement Head access only." />;
  }

  if (referralOff) {
    return (
      <div>
        <PageHeader title="Referral payouts" subtitle="The refer-a-friend program is currently disabled." />
        <div className="rounded border border-line bg-white p-10 text-center shadow-sm">
          <Gift size={28} className="mx-auto mb-3 text-muted" />
          <p className="text-sm text-muted">
            The referral program is turned off, so payout management is unavailable. It can be re-enabled
            by a developer.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Referral payouts"
        subtitle={[
          "Settle the ₹ rewards a referral earned — log the transaction id to credit each beneficiary.",
          describeRegister(th),
        ]
          .filter(Boolean)
          .join(" · ")}
      >
        {role && <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{ROLE_LABEL[role]}</span>}
        <ExportMenu
          title="Referral payouts"
          subtitle={tab === "PAID" ? "Referral expense ledger (paid)" : "Pending referral payouts"}
          fileBase={`dhanboost-referral-payouts-${tab.toLowerCase()}`}
          columns={[
            { header: "Beneficiary", value: (p: ReferralPayout) => p.beneficiaryName ?? `#${p.beneficiaryCustomerId}` },
            { header: "Role", value: (p) => roleLabel(p.beneficiaryRole) },
            { header: "Friend", value: (p) => p.counterpartyName ?? (p.counterpartyCustomerId ? `#${p.counterpartyCustomerId}` : "—") },
            { header: "Amount (₹)", value: (p) => (p.amountPaise / 100).toFixed(2) },
            { header: "Loan #", value: (p) => (p.qualifyingLoanId ? String(p.qualifyingLoanId) : "—") },
            { header: "Status", value: (p) => p.status },
            { header: "Txn ref", value: (p) => p.txnRef ?? "" },
            { header: "Paid by", value: (p) => p.paidBy ?? "" },
            { header: "Paid at", value: (p) => fmtDate(p.paidAt) },
          ]}
          rows={cr.rows}
          scope="all"
        />
        <button
          onClick={() => {
            payoutsQ.refetch();
            expensesQ.refetch();
          }}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {payoutsQ.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {/* Expense summary */}
      <div className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
        <SummaryCard icon={<Clock size={15} />} label="Pending" value={sum ? String(sum.pendingCount) : "—"} sub={sum ? paiseToINR(sum.pendingPaise) : ""} />
        <SummaryCard icon={<Check size={15} />} label="Paid" value={sum ? String(sum.paidCount) : "—"} sub={sum ? paiseToINR(sum.paidPaise) : ""} />
        <SummaryCard icon={<Gift size={15} />} label="Total rewards" value={sum ? String(sum.totalCount) : "—"} sub={sum ? paiseToINR(sum.totalPaise) : ""} />
        <SummaryCard icon={<Gift size={15} />} label="Total paid out" value={sum ? paiseToINR(sum.paidPaise) : "—"} sub="credited to borrowers" />
      </div>

      {/* Tabs */}
      <div className="mb-4 flex gap-2">
        {(["PENDING", "PAID"] as const).map((t) => (
          <button
            key={t}
            onClick={() => {
              if (t === tab) return;
              setTab(t);
              // "What matches has changed" — back to page 1 in one update. The Paid-only filters go
              // too: on Pending their columns are gone, so nothing on screen could clear them.
              const filters = { ...rq.state.filters };
              for (const k of PAID_ONLY_KEYS) delete filters[k];
              rq.apply({ filters });
            }}
            className={`rounded-full px-4 py-1.5 text-sm font-semibold ${
              tab === t ? "bg-navy text-white" : "border border-line text-muted hover:bg-grey-100 hover:text-ink"
            }`}
          >
            {t === "PENDING" ? "Pending" : "Paid"}
          </button>
        ))}
      </div>

      {payoutsQ.isLoading ? (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          <Skeleton variant="table" rows={8} cols={tab === "PENDING" ? 7 : 9} />
        </div>
      ) : payoutsQ.error ? (
        <ErrorState error={payoutsQ.error} onRetry={() => void payoutsQ.refetch()} />
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          <div className="staff-table-scroll">
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>S.No.</th>
                  <Th th={th} col="beneficiaryName" />
                  <Th th={th} col="beneficiaryRole" />
                  <Th th={th} col="counterpartyName" />
                  <Th th={th} col="amountPaise" className="num whitespace-nowrap text-right" />
                  <th className="whitespace-nowrap">Loan #</th>
                  {tab === "PENDING" ? (
                    <th className="text-right">Pay &amp; log txn id</th>
                  ) : (
                    <>
                      <th>Txn ref</th>
                      <Th th={th} col="paidBy" />
                      <Th th={th} col="paidAt" className="whitespace-nowrap" />
                    </>
                  )}
                </tr>
              </thead>
              <tbody className="divide-y divide-line align-top">
                {pageRows.map((p, i) => (
                  <tr key={p.id} className="hover:bg-grey-50">
                    <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                    <td>
                      <span className="block font-medium text-ink">{p.beneficiaryName ?? `#${p.beneficiaryCustomerId}`}</span>
                    </td>
                    <td className="text-muted">{roleLabel(p.beneficiaryRole)}</td>
                    <td className="text-muted">
                      with {p.counterpartyName ?? (p.counterpartyCustomerId ? `#${p.counterpartyCustomerId}` : "—")}
                    </td>
                    <td className="num whitespace-nowrap text-right font-semibold text-ink">{paiseToINR(p.amountPaise)}</td>
                    <td className="whitespace-nowrap text-muted">{p.qualifyingLoanId ? `#${p.qualifyingLoanId}` : "—"}</td>
                    {tab === "PENDING" ? (
                      <td>
                        <div className="flex items-center justify-end gap-2">
                          <Input
                            value={txnRefs[p.id] ?? ""}
                            onChange={(e) => setTxnRefs((m) => ({ ...m, [p.id]: e.target.value }))}
                            placeholder="Bank / UPI txn id"
                            className="!mb-0"
                            inputClassName="w-44"
                          />
                          <button
                            onClick={() => pay.mutate({ id: p.id, txnRef: (txnRefs[p.id] ?? "").trim() })}
                            disabled={pay.isPending || !(txnRefs[p.id] ?? "").trim()}
                            className="btn btn-sm btn-gold disabled:opacity-50"
                          >
                            {pay.isPending && pay.variables?.id === p.id ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />} Mark paid
                          </button>
                        </div>
                      </td>
                    ) : (
                      <>
                        <td className="font-mono text-xs text-ink">{p.txnRef ?? "—"}</td>
                        <td className="text-muted">{p.paidBy ?? "—"}</td>
                        <td className="whitespace-nowrap text-muted">{fmtDate(p.paidAt)}</td>
                      </>
                    )}
                  </tr>
                ))}
                {total === 0 && (
                  <EmptyState
                    // In-table so the headers stay and the filter that emptied it can be cleared.
                    inTable={tab === "PENDING" ? 7 : 9}
                    title={
                      rq.activeCount > 0
                        ? "No payouts match the column filters."
                        : tab === "PENDING"
                          ? "No pending referral payouts."
                          : "No referral rewards paid yet."
                    }
                  />
                )}
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
      {pay.error && <p className="mt-3 text-sm text-error-700">{errMessage(pay.error)}</p>}
    </div>
  );
}

function SummaryCard({ icon, label, value, sub }: { icon: React.ReactNode; label: string; value: string; sub?: string }) {
  return (
    <div className="rounded border border-line bg-white p-4 shadow-sm">
      <div className="mb-1 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-muted">{icon} {label}</div>
      <div className="font-serif text-xl font-bold text-navy">{value}</div>
      {sub ? <div className="text-xs text-muted">{sub}</div> : null}
    </div>
  );
}
