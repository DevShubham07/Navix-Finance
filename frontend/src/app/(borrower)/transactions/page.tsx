"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery, useQueries } from "@tanstack/react-query";
import { ArrowRight, ArrowLeftRight, Loader2 } from "lucide-react";
import { borrowerApi, paiseToINR, type PaymentStatusName } from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";
import { LoanDetailsDialog } from "@/components/borrower/loan-details-dialog";

type LedgerRow = {
  id: string;
  type: "DISBURSAL" | "REPAYMENT";
  loanId: number;
  amountPaise: number;
  date: string | null;
  status: PaymentStatusName | null;
};

/** Status pill styling mirrors the repay page (PaymentStatusName). */
const PAY_STATUS: Record<PaymentStatusName, { label: string; cls: string }> = {
  PENDING_VERIFICATION: { label: "Pending verification", cls: "bg-gold-50 text-gold-dark" },
  VERIFIED: { label: "Verified", cls: "bg-success-50 text-success-700" },
  REJECTED: { label: "Rejected", cls: "bg-error-50 text-error-700" },
};

export default function TransactionsPage() {
  const appsQ = useQuery({ queryKey: ["my-apps"], queryFn: borrowerApi.myApplications });
  const [detailsLoanId, setDetailsLoanId] = React.useState<number | null>(null);

  // Every application that reached disbursal carries a loan id; build the ledger from those.
  const loanIds = (appsQ.data ?? []).filter((a) => a.loanId != null).map((a) => a.loanId as number);

  const loanQs = useQueries({
    queries: loanIds.map((id) => ({ queryKey: ["my-loan", id], queryFn: () => borrowerApi.loan(id) })),
  });
  const repayQs = useQueries({
    queries: loanIds.map((id) => ({ queryKey: ["my-repayments", id], queryFn: () => borrowerApi.repayments(id) })),
  });

  const rows: LedgerRow[] = [];
  loanQs.forEach((lq) => {
    const loan = lq.data;
    if (loan) {
      // DISBURSAL — money credited to the borrower (net of fee + GST).
      rows.push({
        id: `D-${loan.id}`,
        type: "DISBURSAL",
        loanId: loan.id,
        amountPaise: loan.netDisbursedPaise,
        date: loan.disbursedOn,
        status: null,
      });
    }
  });
  repayQs.forEach((rq, i) => {
    const loanId = loanIds[i];
    (rq.data ?? []).forEach((p) => {
      // REPAYMENT — money the borrower paid back.
      rows.push({
        id: `R-${p.id}`,
        type: "REPAYMENT",
        loanId,
        amountPaise: p.amountPaise,
        date: p.paidOn,
        status: p.status,
      });
    });
  });
  rows.sort((a, b) => (b.date ? new Date(b.date).getTime() : 0) - (a.date ? new Date(a.date).getTime() : 0));

  const subLoading = loanQs.some((q) => q.isLoading) || repayQs.some((q) => q.isLoading);

  return (
    <div className="container max-w-content py-10">
      <div className="mb-7">
        <h1 className="mb-0">Transactions</h1>
        <p className="mt-1 text-muted">Money disbursed to you and the repayments you&apos;ve made.</p>
      </div>

      {appsQ.isLoading ? (
        <div className="h-64 animate-pulse rounded border border-line bg-white" />
      ) : appsQ.error ? (
        <div className="rounded border border-error-100 bg-error-50 p-5 text-sm text-error-700">
          Could not load your transactions. Please refresh and try again.
        </div>
      ) : loanIds.length === 0 ? (
        <EmptyState />
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          {subLoading && rows.length === 0 ? (
            <p className="flex items-center justify-center gap-2 px-5 py-10 text-sm text-muted">
              <Loader2 size={15} className="animate-spin" /> Loading transactions…
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
                    <th className="px-4 py-3 font-semibold">Type</th>
                    <th className="px-4 py-3 font-semibold">Loan</th>
                    <th className="px-4 py-3 font-semibold">Date</th>
                    <th className="px-4 py-3 text-right font-semibold">Amount</th>
                    <th className="px-4 py-3 font-semibold">Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {rows.map((r) => (
                    <tr
                      key={r.id}
                      onClick={() => setDetailsLoanId(r.loanId)}
                      className="cursor-pointer hover:bg-grey-100"
                    >
                      <td className="px-4 py-3">
                        <span className="font-semibold text-ink">
                          {r.type === "DISBURSAL" ? "Disbursal" : "Repayment"}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-muted">#{r.loanId}</td>
                      <td className="px-4 py-3 text-muted">{r.date ? formatDate(r.date) : "—"}</td>
                      <td className="px-4 py-3 text-right">
                        <span className={r.type === "DISBURSAL" ? "font-semibold text-success-700" : "font-semibold text-ink"}>
                          {r.type === "DISBURSAL" ? "+ " : "− "}
                          {paiseToINR(r.amountPaise)}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        {r.status ? (
                          <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${PAY_STATUS[r.status].cls}`}>
                            {PAY_STATUS[r.status].label}
                          </span>
                        ) : (
                          <span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">Completed</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      <LoanDetailsDialog
        loanId={detailsLoanId}
        open={detailsLoanId != null}
        onClose={() => setDetailsLoanId(null)}
      />
    </div>
  );
}

function EmptyState() {
  return (
    <div className="rounded border border-line bg-white p-10 text-center shadow-sm">
      <span className="mx-auto mb-3 grid h-14 w-14 place-items-center rounded-full bg-navy-tint text-navy">
        <ArrowLeftRight size={26} />
      </span>
      <h2 className="text-xl">No transactions yet</h2>
      <p className="mb-4 text-muted">Once an advance is disbursed, your disbursal and repayments appear here.</p>
      <Link href="/dashboard" className="btn btn-gold">
        Go to dashboard <ArrowRight size={16} />
      </Link>
    </div>
  );
}
