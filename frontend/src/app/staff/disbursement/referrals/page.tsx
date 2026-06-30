"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Gift, Check, Clock } from "lucide-react";
import { Input } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice, ROLE_LABEL } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { hasPermission } from "@/lib/auth/rbac";
import { staffReferralApi, paiseToINR, type ReferralPayout } from "@/lib/api/applications";

/** Short date for a nullable ISO timestamp. */
function fmtDate(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleDateString("en-IN", { dateStyle: "medium" });
}

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
  const role = useStaffMe().data?.role;
  const qc = useQueryClient();
  const [tab, setTab] = React.useState<"PENDING" | "PAID">("PENDING");
  const [txnRefs, setTxnRefs] = React.useState<Record<number, string>>({});

  const allowed = role ? hasPermission(role, "referral:payout") : false;

  const payoutsQ = useQuery({
    queryKey: ["referral-payouts", tab],
    queryFn: () => staffReferralApi.payouts(tab),
    enabled: allowed,
  });
  const expensesQ = useQuery({
    queryKey: ["referral-expenses"],
    queryFn: staffReferralApi.expenses,
    enabled: allowed,
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
    },
  });

  if (role && !allowed) {
    return <NoAccessNotice message="Disbursement Head access only." />;
  }

  const rows = payoutsQ.data ?? [];
  const sum = expensesQ.data;

  return (
    <div>
      <PageHeader
        title="Referral payouts"
        subtitle="Settle the ₹ rewards a referral earned — log the transaction id to credit each beneficiary."
      >
        {role && <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{ROLE_LABEL[role]}</span>}
        <ExportMenu
          title="Referral payouts"
          subtitle={tab === "PAID" ? "Referral expense ledger (paid)" : "Pending referral payouts"}
          fileBase={`navix-referral-payouts-${tab.toLowerCase()}`}
          columns={[
            { header: "Beneficiary", value: (p: ReferralPayout) => p.beneficiaryName ?? `#${p.beneficiaryApplicantId}` },
            { header: "Role", value: (p) => roleLabel(p.beneficiaryRole) },
            { header: "Friend", value: (p) => p.counterpartyName ?? (p.counterpartyApplicantId ? `#${p.counterpartyApplicantId}` : "—") },
            { header: "Amount (₹)", value: (p) => (p.amountPaise / 100).toFixed(2) },
            { header: "Loan #", value: (p) => (p.qualifyingLoanId ? String(p.qualifyingLoanId) : "—") },
            { header: "Status", value: (p) => p.status },
            { header: "Txn ref", value: (p) => p.txnRef ?? "" },
            { header: "Paid by", value: (p) => p.paidBy ?? "" },
            { header: "Paid at", value: (p) => fmtDate(p.paidAt) },
          ]}
          rows={rows}
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
            onClick={() => setTab(t)}
            className={`rounded-full px-4 py-1.5 text-sm font-semibold ${
              tab === t ? "bg-navy text-white" : "border border-line text-muted hover:bg-grey-100 hover:text-ink"
            }`}
          >
            {t === "PENDING" ? "Pending" : "Paid"}
          </button>
        ))}
      </div>

      {payoutsQ.isLoading ? (
        <div className="h-32 animate-pulse rounded border border-line bg-white" />
      ) : payoutsQ.error ? (
        <p className="text-sm text-error-700">{errMessage(payoutsQ.error)}</p>
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b border-line bg-grey-50 text-left text-xs uppercase tracking-wide text-muted">
                <tr>
                  <th className="px-4 py-2.5">Beneficiary</th>
                  <th className="px-4 py-2.5">Referral</th>
                  <th className="whitespace-nowrap px-4 py-2.5 text-right">Amount</th>
                  <th className="whitespace-nowrap px-4 py-2.5">Loan #</th>
                  {tab === "PENDING" ? (
                    <th className="px-4 py-2.5 text-right">Pay &amp; log txn id</th>
                  ) : (
                    <>
                      <th className="px-4 py-2.5">Txn ref</th>
                      <th className="px-4 py-2.5">Paid by</th>
                      <th className="whitespace-nowrap px-4 py-2.5">Paid at</th>
                    </>
                  )}
                </tr>
              </thead>
              <tbody className="divide-y divide-line align-top">
                {rows.map((p) => (
                  <tr key={p.id} className="hover:bg-grey-50">
                    <td className="px-4 py-3">
                      <span className="block font-medium text-ink">{p.beneficiaryName ?? `#${p.beneficiaryApplicantId}`}</span>
                      <span className="text-xs text-muted">{roleLabel(p.beneficiaryRole)}</span>
                    </td>
                    <td className="px-4 py-3 text-muted">
                      with {p.counterpartyName ?? (p.counterpartyApplicantId ? `#${p.counterpartyApplicantId}` : "—")}
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-right font-semibold text-ink">{paiseToINR(p.amountPaise)}</td>
                    <td className="whitespace-nowrap px-4 py-3 text-muted">{p.qualifyingLoanId ? `#${p.qualifyingLoanId}` : "—"}</td>
                    {tab === "PENDING" ? (
                      <td className="px-4 py-3">
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
                        <td className="px-4 py-3 font-mono text-xs text-ink">{p.txnRef ?? "—"}</td>
                        <td className="px-4 py-3 text-muted">{p.paidBy ?? "—"}</td>
                        <td className="whitespace-nowrap px-4 py-3 text-muted">{fmtDate(p.paidAt)}</td>
                      </>
                    )}
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={tab === "PENDING" ? 5 : 7} className="px-4 py-6 text-center text-muted">
                      {tab === "PENDING" ? "No pending referral payouts." : "No referral rewards paid yet."}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
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
